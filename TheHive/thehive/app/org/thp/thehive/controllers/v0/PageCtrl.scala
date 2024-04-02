package org.thp.thehive.controllers.v0

import org.thp.scalligraph.EntityIdOrName
import org.thp.scalligraph.controllers.{Entrypoint, FieldsParser}
import org.thp.scalligraph.models.{Database, Entity, UMapping}
import org.thp.scalligraph.query._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{IteratorOutput, Traversal}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputPage
import org.thp.thehive.models.{Page, Permissions}
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.PageOps._
import org.thp.thehive.services.{OrganisationSrv, PageSrv}
import play.api.mvc._

import javax.inject.{Inject, Named, Singleton}

@Singleton
class PageCtrl @Inject() (
    override val entrypoint: Entrypoint,
    pageSrv: PageSrv,
    override val db: Database,
    @Named("v0") override val queryExecutor: QueryExecutor,
    override val publicData: PublicPage
) extends QueryCtrl {
  def get(idOrTitle: String): Action[AnyContent] =
    entrypoint("get a page")
      .authRoTransaction(db) { implicit request => implicit graph =>
        pageSrv
          .get(EntityIdOrName(idOrTitle))
          .visible
          .getOrFail("Page")
          .map(p => Results.Ok(p.toJson))
      }

  def create: Action[AnyContent] =
    entrypoint("create a page")
      .extract("page", FieldsParser[InputPage])
      .authPermittedTransaction(db, Permissions.managePage) { implicit request => implicit graph =>
        val page: InputPage = request.body("page")

        pageSrv
          .create(page.toPage)
          .map(p => Results.Created(p.toJson))
      }

  def update(idOrTitle: String): Action[AnyContent] =
    entrypoint("update a page")
      .extract("page", FieldsParser.update("page", publicData.publicProperties))
      .authPermittedTransaction(db, Permissions.managePage) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("page")

        for {
          page    <- pageSrv.get(EntityIdOrName(idOrTitle)).visible.getOrFail("Page")
          updated <- pageSrv.update(page, propertyUpdaters)
        } yield Results.Ok(updated.toJson)
      }

  def delete(idOrTitle: String): Action[AnyContent] =
    entrypoint("delete a page")
      .authPermittedTransaction(db, Permissions.managePage) { implicit request => implicit graph =>
        for {
          page <- pageSrv.get(EntityIdOrName(idOrTitle)).visible.getOrFail("Page")
          _    <- pageSrv.delete(page)
        } yield Results.NoContent
      }
}

@Singleton
class PublicPage @Inject() (pageSrv: PageSrv, organisationSrv: OrganisationSrv) extends PublicData {
  override val entityName: String = "page"
  override val initialQuery: Query =
    Query.init[Traversal.V[Page]]("listPage", (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).pages)
  override val getQuery: ParamQuery[EntityIdOrName] = Query.initWithParam[EntityIdOrName, Traversal.V[Page]](
    "getPage",
    (idOrName, graph, authContext) => pageSrv.get(idOrName)(graph).visible(authContext)
  )
  override def pageQuery(limitedCountThreshold: Long): ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Page], IteratorOutput](
      "page",
      (range, pageSteps, _) => pageSteps.page(range.from, range.to, withTotal = true, limitedCountThreshold)
    )
  override val outputQuery: Query = Query.output[Page with Entity]
  override val publicProperties: PublicProperties = PublicPropertyListBuilder[Page]
    .property("title", UMapping.string)(_.field.updatable)
    .property("content", UMapping.string.set)(_.field.updatable)
    .build

}
