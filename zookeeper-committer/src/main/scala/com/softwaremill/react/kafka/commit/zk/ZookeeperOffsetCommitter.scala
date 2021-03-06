package com.softwaremill.react.kafka.commit.zk

import com.google.common.base.Charsets
import com.softwaremill.react.kafka.commit._
import kafka.common.TopicAndPartition
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.imps.CuratorFrameworkState

import scala.util.Try

/**
 * Based on from https://github.com/cjdev/kafka-rx
 */
class ZookeeperOffsetCommitter(group: String, zk: CuratorFramework) extends OffsetCommitter with SynchronizedCommitter {

  override def start() = {
    if (zk.getState != CuratorFrameworkState.STARTED) {
      zk.start()
      zk.blockUntilConnected()
    }
  }

  override def stop() = zk.close()

  private def getOffsets(topicPartitions: Iterable[TopicAndPartition]): Offsets = {
    topicPartitions.flatMap { topicPartition =>
      val TopicAndPartition(topic, partition) = topicPartition
      val path = getPartitionPath(group, topic, partition)
      Option(zk.checkExists.forPath(path)) match {
        case None => List()
        case Some(filestats) =>
          val bytes = zk.getData.forPath(path)
          val str = new String(bytes, Charsets.UTF_8).trim
          val offset = java.lang.Long.parseLong(str)
          List(topicPartition -> offset)
      }
    }.toMap
  }

  private def setOffsets(offsets: Offsets): Try[OffsetMap] = {
    Try {
      offsets foreach {
        case (topicPartition, offset) =>
          val TopicAndPartition(topic, partition) = topicPartition
          val nodePath = getPartitionPath(group, topic, partition)
          val bytes = offset.toString.getBytes(Charsets.UTF_8)
          Option(zk.checkExists.forPath(nodePath)) match {
            case None =>
              zk.create.creatingParentsIfNeeded.forPath(nodePath, bytes)
            case Some(fileStats) =>
              zk.setData().forPath(nodePath, bytes)
          }
      }
      val newOffsetsInZk: Offsets = getOffsets(offsets.keys)
      OffsetMap(newOffsetsInZk)
    }
  }

  override def getPartitionLock(topicPartition: TopicAndPartition): PartitionLock = {
    val TopicAndPartition(topic, partition) = topicPartition
    val lockPath = s"/locks/reactive-kafka/$topic.$group.$partition"
    new ZookeeperLock(zk, lockPath)
  }

  override def commit(offsetMap: OffsetMap): Try[OffsetMap] = {
    val merge: OffsetMerge = { case (theirs, ours) => ours }
    val offsets = offsetMap.map
    withPartitionLocks(offsets.keys) {
      val zkOffsets = getOffsets(offsets.keys)
      val nextOffsets = merge(zkOffsets, offsets) map {
        case (topicPartition, offset) =>
          // zookeeper stores the *next* offset
          // while kafka messages contain their offset, shift forward 1 for zk format
          topicPartition -> (offset + 1)
      }
      setOffsets(nextOffsets)
    }
  }

  override def tryRestart(): Try[Unit] = {
    Try({
      stop()
      start()
    })
  }
}

