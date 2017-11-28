/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.cassandra.query

import java.nio.ByteBuffer
import java.time.{ LocalDateTime, ZoneOffset }
import java.util.UUID

import akka.actor.ActorSystem
import akka.persistence.PersistentRepr
import akka.persistence.cassandra.journal._
import akka.serialization.{ Serialization, SerializerWithStringManifest }
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.core.{ PreparedStatement, Session }
import akka.persistence.cassandra.formatOffset

private[akka] trait TestTagWriter {
  def system: ActorSystem
  val session: Session
  val serialization: Serialization
  val writePluginConfig: CassandraJournalConfig

  lazy val preparedWriteTagMessage: PreparedStatement = {
    val writeStatements: CassandraStatements = new CassandraStatements {
      def config: CassandraJournalConfig = writePluginConfig
    }
    session.prepare(writeStatements.writeTags)
  }

  def writeTaggedEvent(time: LocalDateTime, pr: PersistentRepr, tags: Set[String], tagPidSequenceNr: Long, bucketSize: BucketSize): Unit = {
    val timestamp = time.toInstant(ZoneOffset.UTC).toEpochMilli
    write(pr, tags, tagPidSequenceNr, uuid(timestamp), bucketSize)
  }

  def writeTaggedEvent(persistent: PersistentRepr, tags: Set[String], tagPidSequenceNr: Long, bucketSize: BucketSize): Unit = {
    val nowUuid = UUIDs.timeBased()
    write(persistent, tags, tagPidSequenceNr, nowUuid, bucketSize)
  }

  def writeTaggedEvent(persistent: PersistentRepr, tags: Set[String], tagPidSequenceNr: Long, uuid: UUID, bucketSize: BucketSize): Unit = {
    write(persistent, tags, tagPidSequenceNr, uuid, bucketSize)
  }

  private def write(pr: PersistentRepr, tags: Set[String], tagPidSequenceNr: Long, uuid: UUID, bucketSize: BucketSize): Unit = {
    val event = pr.payload.asInstanceOf[AnyRef]
    val serializer = serialization.findSerializerFor(event)
    val serialized = ByteBuffer.wrap(serialization.serialize(event).get)

    val serManifest = serializer match {
      case ser2: SerializerWithStringManifest ⇒
        ser2.manifest(pr)
      case _ ⇒
        if (serializer.includeManifest) pr.getClass.getName
        else PersistentRepr.Undefined
    }

    val timeBucket = TimeBucket(UUIDs.unixTimestamp(uuid), bucketSize)

    val bs = preparedWriteTagMessage.bind()

    tags.foreach(tag => {
      bs.setString("tag_name", tag)
      bs.setLong("timebucket", timeBucket.key)
      bs.setUUID("timestamp", uuid)
      bs.setLong("tag_pid_sequence_nr", tagPidSequenceNr)
      bs.setBytes("event", serialized)
      bs.setString("event_manifest", pr.manifest)
      bs.setString("persistence_id", pr.persistenceId)
      bs.setInt("ser_id", serializer.identifier)
      bs.setString("ser_manifest", serManifest)
      bs.setString("writer_uuid", "ManualWrite")
      bs.setLong("sequence_nr", pr.sequenceNr)
      session.execute(bs)
    })

    system.log.debug("Written event: {} Uuid: {} Timebucket: {}", pr.payload, formatOffset(uuid), timeBucket)
  }
}