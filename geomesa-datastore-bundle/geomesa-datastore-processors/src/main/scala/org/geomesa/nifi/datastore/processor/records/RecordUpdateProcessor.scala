/*
 * Copyright (c) 2013-2020 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0 which
 * accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.geomesa.nifi.datastore.processor.records

import java.io.InputStream

import org.apache.nifi.annotation.lifecycle.{OnRemoved, OnScheduled, OnShutdown, OnStopped}
import org.apache.nifi.components.PropertyDescriptor
import org.apache.nifi.expression.ExpressionLanguageScope
import org.apache.nifi.processor.io.InputStreamCallback
import org.apache.nifi.processor.util.StandardValidators
import org.apache.nifi.processor.{ProcessContext, ProcessSession}
import org.apache.nifi.serialization.RecordReaderFactory
import org.apache.nifi.serialization.record.Record
import org.geomesa.nifi.datastore.processor._
import org.geomesa.nifi.datastore.processor.records.RecordUpdateProcessor.Properties.UniqueIdentifierCol
import org.geomesa.nifi.datastore.processor.records.RecordUpdateProcessor.{AttributeFilter, FidFilter}
import org.geotools.data.{DataStore, DataUtilities, Transaction}
import org.geotools.util.factory.Hints
import org.locationtech.geomesa.filter.{FilterHelper, filterToString}
import org.locationtech.geomesa.utils.io.{CloseWithLogging, WithClose}
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.annotation.tailrec
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Processor for updating certain attributes of a feature. As compared to the other ingest processors in
 * 'modify' mode (which require the entire feature to be input), this processor only requires specifying
 * the fields that will be updated
 *
 * @param dataStoreProperties properties exposed through NiFi used to load the data store
 */
abstract class RecordUpdateProcessor(dataStoreProperties: Seq[PropertyDescriptor])
    extends DataStoreProcessor(dataStoreProperties) {

  import RecordUpdateProcessor.Properties.UniqueIdentifierCol
  import Relationships.{FailureRelationship, SuccessRelationship}
  import org.geomesa.nifi.datastore.processor.DataStoreIngestProcessor.Properties.NifiBatchSize
  import org.geomesa.nifi.datastore.processor.records.Properties.RecordReader
  import org.locationtech.geomesa.security.SecureSimpleFeature

  import scala.collection.JavaConverters._

  @volatile
  private var ds: DataStore = _
  private var factory: RecordReaderFactory = _
  private var options: OptionExtractor = _

  @OnScheduled
  def initialize(context: ProcessContext): Unit = {
    logger.info("Initializing")

    ds = loadDataStore(context)

    try {
      factory = context.getProperty(RecordReader).asControllerService(classOf[RecordReaderFactory])
      options = OptionExtractor(context, GeometryEncoding.Wkt)
    } catch {
      case NonFatal(e) => CloseWithLogging(ds); ds = null; throw e
    }

    logger.info(s"Initialized datastore ${ds.getClass.getSimpleName}")
  }

  override def onTrigger(context: ProcessContext, session: ProcessSession): Unit = {
    val flowFiles = session.get(context.getProperty(NifiBatchSize).evaluateAttributeExpressions().asInteger())
    logger.debug(s"Processing ${flowFiles.size()} files in batch")
    if (flowFiles != null && flowFiles.size > 0) {
      flowFiles.asScala.foreach { file =>
        val fullFlowFileName = fullName(file)
        try {
          logger.debug(s"Running ${getClass.getName} on file $fullFlowFileName")
          val opts = options(context, file.getAttributes)
          val id = context.getProperty(UniqueIdentifierCol).evaluateAttributeExpressions(file.getAttributes).getValue
          val filterFactory = if (opts.fidField.contains(id)) { FidFilter } else { new AttributeFilter(id) }

          var success, failure = 0L

          session.read(file, new InputStreamCallback {
            override def process(in: InputStream): Unit = {
              WithClose(factory.createRecordReader(file, in, logger)) { reader =>
                val converter = SimpleFeatureRecordConverter(reader.getSchema, opts)
                val typeName = converter.sft.getTypeName
                val names = converter.sft.getAttributeDescriptors.asScala.map(_.getLocalName)

                checkSchema(converter.sft)

                @tailrec
                def nextRecord: Record = {
                  try {
                    return reader.nextRecord()
                  } catch {
                    case NonFatal(e) =>
                      failure += 1L
                      logger.error("Error reading record from file", e)
                  }
                  nextRecord
                }

                var record = nextRecord
                while (record != null) {
                  try {
                    val sf = converter.convert(record)
                    val filter = filterFactory(sf)
                    try {
                      WithClose(ds.getFeatureWriter(typeName, filter, Transaction.AUTO_COMMIT)) { writer =>
                        if (!writer.hasNext) {
                          logger.warn(s"Filter does not match any features, skipping update: ${filterToString(filter)}")
                          failure += 1L
                        } else {
                          do {
                            val toWrite = writer.next()
                            names.foreach(n => toWrite.setAttribute(n, sf.getAttribute(n)))
                            if (opts.fidField.isDefined) {
                              toWrite.getUserData.put(Hints.PROVIDED_FID, sf.getID)
                            }
                            if (opts.visField.isDefined) {
                              sf.visibility.foreach(toWrite.visibility = _)
                            }
                            writer.write()
                            success += 1L
                          } while (writer.hasNext)
                        }
                      }
                    } catch {
                      case NonFatal(e) =>
                        failure += 1L
                        logger.error(s"Error writing feature to store: '${DataUtilities.encodeFeature(sf)}'", e)
                    }
                  } catch {
                    case NonFatal(e) =>
                      failure += 1L
                      logger.error(s"Error converting record to feature: '${record.toMap.asScala.mkString(",")}'", e)
                  }
                  record = nextRecord
                }
              }
            }
          })

          session.putAttribute(file, "geomesa.ingest.successes", success.toString)
          session.putAttribute(file, "geomesa.ingest.failures", failure.toString)
          session.transfer(file, SuccessRelationship)

          logger.debug(s"Ingested file $fullFlowFileName with $success successes and $failure failures")
        } catch {
          case NonFatal(e) =>
            logger.error(s"Error processing file $fullFlowFileName:", e)
            session.transfer(file, FailureRelationship)
        }
      }
    }
  }

  @OnRemoved
  @OnStopped
  @OnShutdown
  def cleanup(): Unit = {
    logger.info("Processor shutting down")
    val start = System.currentTimeMillis()
    if (ds != null) {
      CloseWithLogging(ds)
      ds = null
    }
    logger.info(s"Shut down in ${System.currentTimeMillis() - start}ms")
  }

  override protected def getProcessorProperties: Seq[PropertyDescriptor] =
    super.getProcessorProperties ++ RecordUpdateProcessor.Props

  /**
   * Check and update the schema in the data store, as needed
   *
   * @param sft simple feature type
   */
  protected def checkSchema(sft: SimpleFeatureType): Unit = {
    val existing = Try(ds.getSchema(sft.getTypeName)).getOrElse(null)
    if (existing == null) {
      throw new RuntimeException(s"Schema '${sft.getTypeName}' does not exist in the data store")
    }

    sft.getAttributeDescriptors.asScala.foreach { d =>
      if (existing.getDescriptor(d.getLocalName) == null) {
        logger.warn(s"Attribute '${d.getLocalName}' does not exist in the schema and will be ignored")
      }
    }
  }
}

object RecordUpdateProcessor {

  import org.geomesa.nifi.datastore.processor.records.Properties._

  private val Props = Seq(
    RecordReader,
    TypeName,
    UniqueIdentifierCol,
    FeatureIdCol,
    GeometrySerializationDefaultWkt,
    VisibilitiesCol
  )

  sealed trait QueryFilter {
    def apply(f: SimpleFeature): Filter
  }

  object FidFilter extends QueryFilter {
    override def apply(f: SimpleFeature): Filter = FilterHelper.ff.id(FilterHelper.ff.featureId(f.getID))
  }

  class AttributeFilter(name: String) extends QueryFilter {
    private val prop = FilterHelper.ff.property(name)
    override def apply(f: SimpleFeature): Filter =
      FilterHelper.ff.equals(prop, FilterHelper.ff.literal(f.getAttribute(name)))
  }

  object Properties {
    val UniqueIdentifierCol: PropertyDescriptor =
      new PropertyDescriptor.Builder()
          .name("unique-identifier-col")
          .displayName("Unique identifier column")
          .description("Column that will be used to uniquely identify the feature for update")
          .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
          .addValidator(StandardValidators.ATTRIBUTE_EXPRESSION_LANGUAGE_VALIDATOR)
          .addValidator(StandardValidators.NON_BLANK_VALIDATOR)
          .required(true)
          .build()
  }
}
