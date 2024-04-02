package org.thp.thehive.migration.th4

import akka.actor.ActorSystem
import org.janusgraph.core.JanusGraph
import org.thp.scalligraph.SingleInstance
import org.thp.scalligraph.janus.JanusDatabase
import org.thp.scalligraph.models.{Database, UpdatableSchema}
import play.api.Configuration

import javax.inject.{Inject, Provider, Singleton}
import scala.collection.JavaConverters._
import scala.collection.immutable

@Singleton
class JanusDatabaseProvider @Inject() (configuration: Configuration, system: ActorSystem, schemas: immutable.Set[UpdatableSchema])
    extends Provider[Database] {

  def dropOtherConnections(db: JanusGraph): Unit = {
    val mgmt = db.openManagement()
    mgmt
      .getOpenInstances
      .asScala
      .filterNot(_.endsWith("(current)"))
      .foreach(mgmt.forceCloseInstance)
    mgmt.commit()
  }

  override lazy val get: Database = {
    val janusDatabase = JanusDatabase.openDatabase(configuration, system)
    dropOtherConnections(janusDatabase)
    val db = new JanusDatabase(
      janusDatabase,
      configuration,
      system,
      new SingleInstance(true)
    )
    db.createSchema(schemas.flatMap(_.modelList).toSeq).get
    db
  }
}
