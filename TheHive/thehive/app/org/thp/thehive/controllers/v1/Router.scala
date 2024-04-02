package org.thp.thehive.controllers.v1

import play.api.routing.Router.Routes
import play.api.routing.SimpleRouter
import play.api.routing.sird._

import javax.inject.{Inject, Singleton}

@Singleton
class Router @Inject() (
    adminCtrl: AdminCtrl,
    authenticationCtrl: AuthenticationCtrl,
    alertCtrl: AlertCtrl,
    // attachmentCtrl: AttachmentCtrl,
    auditCtrl: AuditCtrl,
    caseCtrl: CaseCtrl,
    caseTemplateCtrl: CaseTemplateCtrl,
    // configCtrl: ConfigCtrl,
    customFieldCtrl: CustomFieldCtrl,
    // dashboardCtrl: DashboardCtrl,
    describeCtrl: DescribeCtrl,
    logCtrl: LogCtrl,
    monitoringCtrl: MonitoringCtrl,
    observableCtrl: ObservableCtrl,
    observableTypeCtrl: ObservableTypeCtrl,
    organisationCtrl: OrganisationCtrl,
    // pageCtrl: PageCtrl,
    // permissionCtrl: PermissionCtrl,
    patternCtrl: PatternCtrl,
    procedureCtrl: ProcedureCtrl,
    profileCtrl: ProfileCtrl,
    tagCtrl: TagCtrl,
    taskCtrl: TaskCtrl,
    shareCtrl: ShareCtrl,
    taxonomyCtrl: TaxonomyCtrl,
    // shareCtrl: ShareCtrl,
    userCtrl: UserCtrl,
    statusCtrl: StatusCtrl
    // streamCtrl: StreamCtrl,
) extends SimpleRouter {

  override def routes: Routes = {
    case GET(p"/status") => statusCtrl.get
//    GET  /health                              controllers.StatusCtrl.health

    case GET(p"/admin/check/stats")                                                            => adminCtrl.checkStats
    case GET(p"/admin/check/$name/trigger")                                                    => adminCtrl.triggerGlobalCheck(name)
    case POST(p"/admin/check/$name/global/trigger")                                            => adminCtrl.triggerGlobalCheck(name)
    case POST(p"/admin/check/$name/dedup/trigger")                                             => adminCtrl.triggerDedup(name)
    case POST(p"/admin/check/cancel")                                                          => adminCtrl.cancelCurrentCheck
    case GET(p"/admin/index/status")                                                           => adminCtrl.indexStatus
    case POST(p"/admin/index/$name/reindex")                                                   => adminCtrl.reindex(name)
    case POST(p"/admin/index/$name/rebuild")                                                   => adminCtrl.rebuild(name)
    case GET(p"/admin/log/set/$packageName/$level")                                            => adminCtrl.setLogLevel(packageName, level)
    case POST(p"/admin/schema/repair/$schemaName" ? q_o"select=$select" ? q_o"filter=$filter") => adminCtrl.schemaRepair(schemaName, select, filter)
    case POST(p"/admin/schema/info/$schemaName" ? q_o"select=$select" ? q_o"filter=$filter")   => adminCtrl.schemaInfo(schemaName, select, filter)

//    GET      /logout                              controllers.AuthenticationCtrl.logout()
    case GET(p"/logout")                 => authenticationCtrl.logout
    case POST(p"/logout")                => authenticationCtrl.logout
    case POST(p"/login")                 => authenticationCtrl.login
    case POST(p"/auth/totp/set")         => authenticationCtrl.totpSetSecret
    case POST(p"/auth/totp/unset")       => authenticationCtrl.totpUnsetSecret(None)
    case POST(p"/auth/totp/unset/$user") => authenticationCtrl.totpUnsetSecret(Some(user))

    case POST(p"/case")                     => caseCtrl.create
    case GET(p"/case/$caseId")              => caseCtrl.get(caseId)
    case PATCH(p"/case/$caseId")            => caseCtrl.update(caseId)
    case POST(p"/case/_merge/$caseIds")     => caseCtrl.merge(caseIds)
    case DELETE(p"/case/$caseId")           => caseCtrl.delete(caseId)
    case DELETE(p"/case/customField/$cfId") => caseCtrl.deleteCustomField(cfId)
    //    case PATCH(p"api/case/_bulk") =>                          caseCtrl.bulkUpdate()
//    case POST(p"/case/_stats") =>                        caseCtrl.stats()
//    case GET(p"/case/$caseId/links") =>                  caseCtrl.linkedCases(caseId)

    case POST(p"/case/$caseId/observable")    => observableCtrl.createInCase(caseId)
    case POST(p"/alert/$alertId/artifact")    => observableCtrl.createInAlert(alertId)
    case GET(p"/observable/$observableId")    => observableCtrl.get(observableId)
    case DELETE(p"/observable/$observableId") => observableCtrl.delete(observableId)
    case PATCH(p"/observable/_bulk")          => observableCtrl.bulkUpdate
    case PATCH(p"/observable/$observableId")  => observableCtrl.update(observableId)
//    case GET(p"/observable/$observableId/similar") => observableCtrl.findSimilar(observableId)
    case POST(p"/observable/$observableId/shares")         => shareCtrl.shareObservable(observableId)
    case PUT(p"/observable/type/update/$fromType/$toType") => observableCtrl.updateAllTypes(fromType, toType)

    case GET(p"/caseTemplate")                   => caseTemplateCtrl.list
    case POST(p"/caseTemplate")                  => caseTemplateCtrl.create
    case GET(p"/caseTemplate/$caseTemplateId")   => caseTemplateCtrl.get(caseTemplateId)
    case PATCH(p"/caseTemplate/$caseTemplateId") => caseTemplateCtrl.update(caseTemplateId)
    //case DELETE(p"/caseTemplate/$caseTemplateId") => caseTemplateCtrl.delete(caseTemplateId)

    case POST(p"/user")                                                   => userCtrl.create
    case GET(p"/user/current")                                            => userCtrl.current
    case GET(p"/user/$userId")                                            => userCtrl.get(userId)
    case PATCH(p"/user/$userId")                                          => userCtrl.update(userId)
    case DELETE(p"/user/$userId")                                         => userCtrl.lock(userId)
    case DELETE(p"/user/$userId/force" ? q_o"organisation=$organisation") => userCtrl.delete(userId, organisation)
    case POST(p"/user/$userId/password/set")                              => userCtrl.setPassword(userId)
    case POST(p"/user/$userId/password/change")                           => userCtrl.changePassword(userId)
    case GET(p"/user/$userId/key")                                        => userCtrl.getKey(userId)
    case DELETE(p"/user/$userId/key")                                     => userCtrl.removeKey(userId)
    case POST(p"/user/$userId/key/renew")                                 => userCtrl.renewKey(userId)
    case GET(p"/user/$userId/avatar$file*")                               => userCtrl.avatar(userId)
    case POST(p"/user/$userId/reset")                                     => userCtrl.resetFailedAttempts(userId)

    case POST(p"/organisation")                  => organisationCtrl.create
    case GET(p"/organisation/$organisationId")   => organisationCtrl.get(organisationId)
    case PATCH(p"/organisation/$organisationId") => organisationCtrl.update(organisationId)

    case DELETE(p"/case/shares")                               => shareCtrl.removeShares()
    case POST(p"/case/$caseId/shares")                         => shareCtrl.shareCase(caseId)
    case DELETE(p"/case/$caseId/shares")                       => shareCtrl.removeShares(caseId)
    case DELETE(p"/task/$taskId/shares")                       => shareCtrl.removeTaskShares(taskId)
    case DELETE(p"/observable/$observableId/shares")           => shareCtrl.removeObservableShares(observableId)
    case GET(p"/case/$caseId/shares")                          => shareCtrl.listShareCases(caseId)
    case GET(p"/case/$caseId/task/$taskId/shares")             => shareCtrl.listShareTasks(caseId, taskId)
    case GET(p"/case/$caseId/observable/$observableId/shares") => shareCtrl.listShareObservables(caseId, observableId)
    case POST(p"/case/task/$taskId/shares")                    => shareCtrl.shareTask(taskId)
    case DELETE(p"/case/share/$shareId")                       => shareCtrl.removeShare(shareId)
    case PATCH(p"/case/share/$shareId")                        => shareCtrl.updateShare(shareId)

    case GET(p"/task")                                => taskCtrl.list
    case POST(p"/task")                               => taskCtrl.create
    case GET(p"/task/$taskId")                        => taskCtrl.get(taskId)
    case PATCH(p"/task/_bulk")                        => taskCtrl.bulkUpdate
    case PATCH(p"/task/$taskId")                      => taskCtrl.update(taskId)
    case GET(p"/task/$taskId/actionRequired")         => taskCtrl.isActionRequired(taskId)
    case PUT(p"/task/$taskId/actionRequired/$orgaId") => taskCtrl.actionRequired(taskId, orgaId, required = true)
    case PUT(p"/task/$taskId/actionDone/$orgaId")     => taskCtrl.actionRequired(taskId, orgaId, required = false)
    // POST     /case/:caseId/task/_search           controllers.TaskCtrl.findInCase(caseId)
    // POST     /case/task/_stats                    controllers.TaskCtrl.stats()

    case POST(p"/task/$taskId/log") => logCtrl.create(taskId)
    case PATCH(p"/log/$logId")      => logCtrl.update(logId)
    case DELETE(p"/log/$logId")     => logCtrl.delete(logId)

    case GET(p"/customField")  => customFieldCtrl.list
    case POST(p"/customField") => customFieldCtrl.create

    case POST(p"/alert")                   => alertCtrl.create
    case GET(p"/alert/$alertId")           => alertCtrl.get(alertId)
    case PATCH(p"/alert/$alertId")         => alertCtrl.update(alertId)
    case POST(p"/alert/$alertId/read")     => alertCtrl.markAsRead(alertId)
    case POST(p"/alert/$alertId/unread")   => alertCtrl.markAsUnread(alertId)
    case POST(p"/alert/$alertId/follow")   => alertCtrl.followAlert(alertId)
    case POST(p"/alert/$alertId/unfollow") => alertCtrl.unfollowAlert(alertId)
    case POST(p"/alert/$alertId/case")     => alertCtrl.createCase(alertId)
    case POST(p"/alert/fixCaseLink")       => alertCtrl.fixCaseLink
    // PATCH    /alert/_bulk                         controllers.AlertCtrl.bulkUpdate()
//    DELETE   /alert/:alertId                      controllers.AlertCtrl.delete(alertId)
//    POST     /alert/:alertId/merge/:caseId        controllers.AlertCtrl.mergeWithCase(alertId, caseId)

    case POST(p"/taxonomy")                   => taxonomyCtrl.create
    case POST(p"/taxonomy/import-zip")        => taxonomyCtrl.importZip
    case GET(p"/taxonomy/$taxoId")            => taxonomyCtrl.get(taxoId)
    case PUT(p"/taxonomy/$taxoId/activate")   => taxonomyCtrl.toggleActivation(taxoId, isActive = true)
    case PUT(p"/taxonomy/$taxoId/deactivate") => taxonomyCtrl.toggleActivation(taxoId, isActive = false)
    case DELETE(p"/taxonomy/$taxoId")         => taxonomyCtrl.delete(taxoId)

    case GET(p"/audit") => auditCtrl.flow
    // GET      /flow                                controllers.AuditCtrl.flow(rootId: Option[String], count: Option[Int])
    // GET      /audit                               controllers.AuditCtrl.find()
    // POST     /audit/_search                       controllers.AuditCtrl.find()
    // POST     /audit/_stats                        controllers.AuditCtrl.stats()

    case POST(p"/pattern/import/attack") => patternCtrl.importMitre
    case GET(p"/pattern/$patternId")     => patternCtrl.get(patternId)
    case GET(p"/pattern/case/$caseId")   => patternCtrl.getCasePatterns(caseId)
    case DELETE(p"/pattern/$patternId")  => patternCtrl.delete(patternId)

    case POST(p"/procedure")                => procedureCtrl.create
    case GET(p"/procedure/$procedureId")    => procedureCtrl.get(procedureId)
    case PATCH(p"/procedure/$procedureId")  => procedureCtrl.update(procedureId)
    case DELETE(p"/procedure/$procedureId") => procedureCtrl.delete(procedureId)

    case POST(p"/profile")              => profileCtrl.create
    case GET(p"/profile/$profileId")    => profileCtrl.get(profileId)
    case PATCH(p"/profile/$profileId")  => profileCtrl.update(profileId)
    case DELETE(p"/profile/$profileId") => profileCtrl.delete(profileId)

    case GET(p"/tag/$id")    => tagCtrl.get(id)
    case PATCH(p"/tag/$id")  => tagCtrl.update(id)
    case DELETE(p"/tag/$id") => tagCtrl.delete(id)

    case GET(p"/describe/_all")       => describeCtrl.describeAll
    case GET(p"/describe/$modelName") => describeCtrl.describe(modelName)

    case GET(p"/observable/type/$idOrName")    => observableTypeCtrl.get(idOrName)
    case POST(p"/observable/type")             => observableTypeCtrl.create
    case DELETE(p"/observable/type/$idOrName") => observableTypeCtrl.delete(idOrName)

    case GET(p"/monitor/disk") => monitoringCtrl.diskUsage
  }
}
