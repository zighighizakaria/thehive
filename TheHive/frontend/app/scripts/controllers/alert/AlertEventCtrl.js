(function () {
    'use strict';
    angular.module('theHiveControllers')
        .controller('AlertEventCtrl', function ($scope, $rootScope, $state, $uibModal, $uibModalInstance, ModalUtilsSrv, TagSrv, AuthenticationSrv, CustomFieldsSrv, CaseResolutionStatus, AlertingSrv, NotificationSrv, UiSettingsSrv, clipboard, event, templates, readonly) {
            var self = this;
            var eventId = event._id;

            self.eventId = event._id;
            self.readonly = readonly;
            self.templates = _.pluck(templates, 'name');
            self.CaseResolutionStatus = CaseResolutionStatus;
            self.event = event;
            self.canEdit = AuthenticationSrv.hasPermission('manageAlert');

            self.loading = true;

            self.customFieldsCache = CustomFieldsSrv;

            self.counts = {
                observables: 0,
                similarCases: 0
            };

            self.hideEmptyCaseButton = UiSettingsSrv.hideEmptyCaseButton();

            self.updateObservableCount = function (count) {
                self.counts.observables = count;
            };

            self.updateSimilarCasesCount = function (count) {
                self.counts.similarCases = count;
            };

            self.getCustomFieldName = function (fieldDef) {
                return 'customFields.' + fieldDef.reference + '.' + fieldDef.type;
            };

            self.getTags = function (selection) {
                var tags = [];

                angular.forEach(selection, function (tag) {
                    tags.push(tag.text);
                });

                return tags;
            };

            self.getAlertTags = function (query) {
                return TagSrv.autoComplete(query);
            };

            self.load = function () {
                AlertingSrv.get(eventId).then(function (data) {
                    self.event = data;
                    self.loading = false;
                }, function (response) {
                    self.loading = false;
                    NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                    $uibModalInstance.dismiss();
                });
            };

            self.updateField = function (fieldName, newValue) {
                var field = {};
                field[fieldName] = newValue;

                return AlertingSrv.update(self.event._id, field)
                    .then(function () {
                        NotificationSrv.log('Alert updated successfully', 'success');
                    })
                    .catch(function (response) {
                        NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                    });
            };

            self.import = function () {
                self.loading = true;
                AlertingSrv.create(self.event._id, {
                    caseTemplate: self.event.caseTemplate
                }).then(function (response) {
                    $uibModalInstance.dismiss();

                    $rootScope.$broadcast('alert:event-imported');

                    $state.go('app.case.details', {
                        caseId: response.data.id
                    });
                }, function (response) {
                    self.loading = false;
                    NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                });
            };

            self.mergeIntoCase = function (caseId) {
                self.loading = true;
                AlertingSrv.mergeInto(self.event._id, caseId)
                    .then(function (response) {
                        $uibModalInstance.dismiss();

                        $rootScope.$broadcast('alert:event-imported');

                        $state.go('app.case.details', {
                            caseId: response.data.id
                        });
                    }, function (response) {
                        self.loading = false;
                        NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                    });
            };

            self.merge = function () {
                var caseModal = $uibModal.open({
                    templateUrl: 'views/partials/case/case.merge.html',
                    controller: 'CaseMergeModalCtrl',
                    controllerAs: 'dialog',
                    size: 'lg',
                    resolve: {
                        source: function () {
                            return self.event;
                        },
                        title: function () {
                            return 'Merge Alert: ' + self.event.title;
                        },
                        prompt: function () {
                            return self.event.title;
                        },
                        filter: function () {
                            var skipResolvedCases = UiSettingsSrv.disallowMergeAlertInResolvedSimilarCases() === true;

                            if (skipResolvedCases) {
                                return {
                                    _ne: {
                                        _field: 'status',
                                        _value: 'Resolved'
                                    }
                                }
                            }

                            return null;
                        }
                    }
                });

                caseModal.result.then(function (selectedCase) {
                    self.mergeIntoCase(selectedCase._id);
                }).catch(function (err) {
                    if (err && !_.isString(err)) {
                        NotificationSrv.error('AlertEventCtrl', err.data, err.status);
                    }
                });
            };

            this.follow = function () {
                var fn = angular.noop;

                if (self.event.follow === true) {
                    fn = AlertingSrv.unfollow;
                } else {
                    fn = AlertingSrv.follow;
                }

                fn(self.event._id).then(function () {
                    $uibModalInstance.close();
                }).catch(function (response) {
                    NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                });
            };

            this.delete = function () {
                ModalUtilsSrv.confirm('Remove Alert', 'Are you sure you want to delete this Alert?', {
                    okText: 'Yes, remove it',
                    flavor: 'danger'
                }).then(function () {
                    AlertingSrv.forceRemove(self.event._id)
                        .then(function () {
                            $uibModalInstance.close();
                            NotificationSrv.log('Alert has been permanently deleted', 'success');
                        })
                        .catch(function (response) {
                            NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                        });
                });

            };

            this.canMarkAsRead = AlertingSrv.canMarkAsRead;
            this.canMarkAsUnread = AlertingSrv.canMarkAsUnread;

            this.markAsRead = function () {
                var fn = angular.noop;

                if (this.canMarkAsRead(this.event)) {
                    fn = AlertingSrv.markAsRead;
                } else {
                    fn = AlertingSrv.markAsUnread;
                }

                fn(this.event._id).then(function ( /*data*/) {
                    $uibModalInstance.close();
                }, function (response) {
                    NotificationSrv.error('AlertEventCtrl', response.data, response.status);
                });
            };

            self.cancel = function () {
                $uibModalInstance.dismiss();
            };

            self.copyId = function (id) {
                clipboard.copyText(id);
                NotificationSrv.log('Alert ID has been copied to clipboard', 'success');
            };

            this.$onInit = function () {
                self.load();
            };
        });
})();
