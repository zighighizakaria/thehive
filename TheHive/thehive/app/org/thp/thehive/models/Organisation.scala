package org.thp.thehive.models

import org.thp.scalligraph.models.{DefineIndex, Entity, IndexType}
import org.thp.scalligraph.{BuildEdgeEntity, BuildVertexEntity, EntityId}

import java.util.Date

@BuildVertexEntity
@DefineIndex(IndexType.unique, "name")
case class Organisation(name: String, description: String)

object Organisation {
  val administration: Organisation     = Organisation("admin", "organisation for administration")
  val initialValues: Seq[Organisation] = Seq(administration)
}

@BuildEdgeEntity[Organisation, Share]
case class OrganisationShare()

@BuildEdgeEntity[Organisation, Organisation]
case class OrganisationOrganisation()

@BuildEdgeEntity[Organisation, Taxonomy]
case class OrganisationTaxonomy()

case class RichOrganisation(organisation: Organisation with Entity, links: Seq[Organisation with Entity]) {
  def name: String               = organisation.name
  def description: String        = organisation.description
  def _id: EntityId              = organisation._id
  def _createdAt: Date           = organisation._createdAt
  def _createdBy: String         = organisation._createdBy
  def _updatedAt: Option[Date]   = organisation._updatedAt
  def _updatedBy: Option[String] = organisation._updatedBy
}
