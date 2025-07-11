/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.sharding.internal

import org.apache.pekko
import pekko.actor.ActorLogging
import pekko.actor.Props
import pekko.annotation.InternalApi
import pekko.cluster.sharding.ClusterShardingSerializable
import pekko.cluster.sharding.ClusterShardingSettings
import pekko.cluster.sharding.ShardRegion
import pekko.cluster.sharding.ShardRegion.EntityId
import pekko.persistence.DeleteMessagesFailure
import pekko.persistence.DeleteMessagesSuccess
import pekko.persistence.DeleteSnapshotsFailure
import pekko.persistence.DeleteSnapshotsSuccess
import pekko.persistence.PersistentActor
import pekko.persistence.RecoveryCompleted
import pekko.persistence.SaveSnapshotFailure
import pekko.persistence.SaveSnapshotSuccess
import pekko.persistence.SnapshotOffer
import pekko.persistence.SnapshotSelectionCriteria

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] object EventSourcedRememberEntitiesShardStore {

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange extends ClusterShardingSerializable

  /**
   * Persistent state of the Shard.
   */
  final case class State private[pekko] (entities: Set[EntityId] = Set.empty) extends ClusterShardingSerializable

  /**
   * `State` change for starting a set of entities in this `Shard`
   */
  final case class EntitiesStarted(entities: Set[EntityId]) extends StateChange

  case object StartedAck

  /**
   * `State` change for an entity which has terminated.
   */
  final case class EntitiesStopped(entities: Set[EntityId]) extends StateChange

  def props(typeName: String, shardId: ShardRegion.ShardId, settings: ClusterShardingSettings): Props =
    Props(new EventSourcedRememberEntitiesShardStore(typeName, shardId, settings))
}

/**
 * INTERNAL API
 *
 * Persistent actor keeping the state for Pekko Persistence backed remember entities (enabled through `state-store-mode=persistence`).
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
@InternalApi
private[pekko] final class EventSourcedRememberEntitiesShardStore(
    typeName: String,
    shardId: ShardRegion.ShardId,
    settings: ClusterShardingSettings)
    extends PersistentActor
    with ActorLogging {

  import EventSourcedRememberEntitiesShardStore._
  import settings.tuningParameters._

  private val maxUpdatesPerWrite = context.system.settings.config
    .getInt("pekko.cluster.sharding.event-sourced-remember-entities-store.max-updates-per-write")

  log.debug("Starting up EventSourcedRememberEntitiesStore")
  private var state = State()
  override def persistenceId = s"/sharding/${typeName}Shard/$shardId"
  override def journalPluginId: String = settings.journalPluginId
  override def snapshotPluginId: String = settings.snapshotPluginId

  override def receiveRecover: Receive = {
    case EntitiesStarted(ids)              => state = state.copy(state.entities.union(ids))
    case EntitiesStopped(ids)              => state = state.copy(state.entities.diff(ids))
    case SnapshotOffer(_, snapshot: State) => state = snapshot
    case RecoveryCompleted                 =>
      log.debug("Recovery completed for shard [{}] with [{}] entities", shardId, state.entities.size)
  }

  override def receiveCommand: Receive = {

    case RememberEntitiesShardStore.Update(started, stopped) =>
      val events =
        (if (started.nonEmpty) EntitiesStarted(started) :: Nil else Nil) :::
        (if (stopped.nonEmpty) EntitiesStopped(stopped) :: Nil else Nil)
      var left = events.size
      var saveSnap = false
      def persistEventsAndHandleComplete(evts: List[StateChange]): Unit = {
        persistAll(evts) { _ =>
          left -= 1
          saveSnap = saveSnap || isSnapshotNeeded
          if (left == 0) {
            sender() ! RememberEntitiesShardStore.UpdateDone(started, stopped)
            state = state.copy(state.entities.union(started).diff(stopped))
            if (saveSnap) {
              saveSnapshot()
            }
          }
        }
      }
      if (left <= maxUpdatesPerWrite) {
        // optimized when batches are small
        persistEventsAndHandleComplete(events)
      } else {
        // split up in several writes so we don't hit journal limit
        events.grouped(maxUpdatesPerWrite).foreach(persistEventsAndHandleComplete)
      }

    case RememberEntitiesShardStore.GetEntities =>
      sender() ! RememberEntitiesShardStore.RememberedEntities(state.entities)

    case e: SaveSnapshotSuccess =>
      log.debug("Snapshot saved successfully")
      internalDeleteMessagesBeforeSnapshot(e, keepNrOfBatches, snapshotAfter)

    case SaveSnapshotFailure(_, reason) =>
      log.warning("Snapshot failure: [{}]", reason.getMessage)

    case DeleteMessagesSuccess(toSequenceNr) =>
      val deleteTo = toSequenceNr - 1
      // keeping one additional batch of messages in case snapshotAfter has been delayed to the end of a processed batch
      val keepNrOfBatchesWithSafetyBatch = if (keepNrOfBatches == 0) 0 else keepNrOfBatches + 1
      val deleteFrom = math.max(0, deleteTo - (keepNrOfBatchesWithSafetyBatch * snapshotAfter))
      log.debug(
        "Messages to [{}] deleted successfully. Deleting snapshots from [{}] to [{}]",
        toSequenceNr,
        deleteFrom,
        deleteTo)
      deleteSnapshots(SnapshotSelectionCriteria(minSequenceNr = deleteFrom, maxSequenceNr = deleteTo))

    case DeleteMessagesFailure(reason, toSequenceNr) =>
      log.warning("Messages to [{}] deletion failure: [{}]", toSequenceNr, reason.getMessage)

    case DeleteSnapshotsSuccess(m) =>
      log.debug("Snapshots matching [{}] deleted successfully", m)

    case DeleteSnapshotsFailure(m, reason) =>
      log.warning("Snapshots matching [{}] deletion failure: [{}]", m, reason.getMessage)

  }

  override def postStop(): Unit = {
    super.postStop()
    log.debug("Store stopping")
  }

  def saveSnapshotWhenNeeded(): Unit = {
    if (isSnapshotNeeded) {
      saveSnapshot()
    }
  }

  private def saveSnapshot(): Unit = {
    log.debug("Saving snapshot, sequence number [{}]", snapshotSequenceNr)
    saveSnapshot(state)
  }

  private def isSnapshotNeeded = {
    lastSequenceNr % snapshotAfter == 0 && lastSequenceNr != 0
  }
}
