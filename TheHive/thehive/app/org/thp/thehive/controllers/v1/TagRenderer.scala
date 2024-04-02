package org.thp.thehive.controllers.v1

import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, Traversal}
import org.thp.thehive.models.Tag
import org.thp.thehive.services.OrganisationSrv
import org.thp.thehive.services.TagOps._
import play.api.libs.json._

import java.util.{Map => JMap}

trait TagRenderer extends BaseRenderer[Tag] {
  val limitedCountThreshold: Long
  val organisationSrv: OrganisationSrv

  def usageStats(implicit
      authContext: AuthContext
  ): Traversal.V[Tag] => Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]] =
    t =>
      t.project(
        _.by(_.value(_.predicate))
          .by(_.caseTemplate.limitedCount(limitedCountThreshold))
      ).domainMap {
        case (tag, caseTemplateCount) =>
          Json.obj(
            "case" -> t
              .graph
              .indexCountQuery(
                s"""v."_label":Case AND """ +
                  s"v.tags:${t.graph.escapeQueryParameter(tag)} AND " +
                  s"v.organisationIds:${organisationSrv.currentId(t.graph, authContext).value}"
              ),
            "alert" -> t
              .graph
              .indexCountQuery(
                s"""v."_label":Alert AND """ +
                  s"v.tags:${t.graph.escapeQueryParameter(tag)} AND " +
                  s"v.organisationId:${organisationSrv.currentId(t.graph, authContext).value}"
              ),
            "observable" -> t
              .graph
              .indexCountQuery(
                s"""v."_label":Observable AND """ +
                  s"v.tags:${t.graph.escapeQueryParameter(tag)} AND " +
                  s"v.organisationIds:${organisationSrv.currentId(t.graph, authContext).value}"
              ),
            "caseTemplate" -> caseTemplateCount
          )
      }
//  def usageStats(implicit
//      authContext: AuthContext
//  ): Traversal.V[Tag] => Traversal[JsObject, JMap[String, Any], Converter[JsObject, JMap[String, Any]]] =
//    _.project(
//      _.by(_.`case`.limitedCount(limitedCountThreshold))
//        .by(_.alert.limitedCount(limitedCountThreshold))
//        .by(_.observable.limitedCount(limitedCountThreshold))
//        .by(_.caseTemplate.limitedCount(limitedCountThreshold))
//    ).domainMap {
//      case (caseCount, alertCount, observableCount, caseTemplateCount) =>
//        Json.obj(
//          "case"         -> caseCount,
//          "alert"        -> alertCount,
//          "observable"   -> observableCount,
//          "caseTemplate" -> caseTemplateCount
//        )
//    }

  def tagStatsRenderer(extraData: Set[String])(implicit
      authContext: AuthContext
  ): Traversal.V[Tag] => JsTraversal = { implicit traversal =>
    baseRenderer(
      extraData,
      traversal,
      {
        case (f, "usage") => addData("usage", f)(usageStats)
        case (f, _)       => f
      }
    )
  }
}
