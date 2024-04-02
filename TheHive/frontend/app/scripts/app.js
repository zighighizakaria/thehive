angular.module('theHiveControllers', []);
angular.module('theHiveServices', []);
angular.module('theHiveFilters', []);
angular.module('theHiveDirectives', []);
angular.module('theHiveComponents', []);

angular.module('thehive', [
    'ngAnimate',
    'ngMessages',
    'ngSanitize',
    'ui.bootstrap',
    'ui.router',
    'ui.sortable',
    'timer',
    'angularMoment',
    'ngCsv',
    'ngTagsInput',
    'ngResource',
    'ui-notification',
    'angular-clipboard',
    'LocalStorageModule',
    'angular-markdown-editor',
    'hc.marked',
    'hljs',
    'ui.ace',
    'angular-page-loader',
    'naif.base64',
    'images-resizer',
    'duScroll',
    'dndLists',
    'colorpicker.module',
    'btorfs.multiselect',
    'ja.qr',
    'theHiveControllers',
    'theHiveServices',
    'theHiveFilters',
    'theHiveDirectives',
    'theHiveComponents'
])
    .config(function ($resourceProvider) {
        'use strict';

        $resourceProvider.defaults.stripTrailingSlashes = true;
    })
    .config(function ($compileProvider) {
        'use strict';
        $compileProvider.debugInfoEnabled(false);
    })
    .config(function ($stateProvider, $urlRouterProvider) {
        'use strict';

        //$urlRouterProvider.otherwise('/index');
        $urlRouterProvider.otherwise(function ($injector) {
            $injector.invoke(['$state', function ($state) {
                $state.go('app.index');
            }]);
        });

        $stateProvider
            .state('login', {
                url: '/login',
                controller: 'AuthenticationCtrl',
                templateUrl: 'views/login.html',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                },
                params: {
                    autoLogin: false
                },
                title: 'Login'
            })
            .state('live', {
                url: '/live',
                templateUrl: 'views/partials/live.html',
                controller: 'LiveCtrl',
                title: 'Live feed'
            })
            .state('maintenance', {
                url: '/maintenance',
                templateUrl: 'views/maintenance.html',
                controller: 'MigrationCtrl',
                title: 'Database migration'
            })
            .state('app', {
                url: '/',
                abstract: true,
                templateUrl: 'views/app.html',
                controller: 'RootCtrl',
                resolve: {
                    currentUser: function ($q, $state, UserSrv, AuthenticationSrv, NotificationSrv) {
                        var deferred = $q.defer();

                        AuthenticationSrv.current()
                            .then(function (userData) {
                                // Prepare user cache
                                UserSrv.loadCache(userData.organisation);

                                return deferred.resolve(userData);
                            })
                            .catch(function (err) {
                                NotificationSrv.error('App', err.data, err.status);
                                return deferred.reject(err);
                                //return deferred.resolve(err.status === 520 ? err.status : null);
                            });

                        return deferred.promise;
                    },
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    },
                    appLayout: function ($q, $rootScope, AppLayoutSrv) {
                        AppLayoutSrv.init();
                        return $q.resolve();
                    },
                    uiConfig: function ($q, UiSettingsSrv) {
                        return UiSettingsSrv.all();
                    },
                    taxonomyCache: function (TaxonomyCacheSrv) {
                        return TaxonomyCacheSrv.all();
                    }
                }
            })
            .state('app.index', {
                url: 'index',
                onEnter: function ($state, AuthenticationSrv) {
                    $state.go(AuthenticationSrv.getHomePage(), {}, { reload: true });
                }
            })
            .state('app.main', {
                url: 'main/{viewId}',
                params: {
                    viewId: 'mytasks'
                },
                templateUrl: 'views/partials/main/list.html',
                controller: 'MainPageCtrl',
                controllerAs: '$vm',
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.cases', {
                url: 'cases',
                templateUrl: 'views/partials/case/case.list.html',
                controller: 'CaseListCtrl',
                controllerAs: '$vm',
                title: 'Cases',
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.search', {
                url: 'search?q',
                templateUrl: 'views/partials/search/list.html',
                controller: 'SearchCtrl',
                title: 'Search',
                resolve: {
                    metadata: function ($q, DashboardSrv, NotificationSrv) {
                        var defer = $q.defer();

                        DashboardSrv.getMetadata()
                            .then(function (response) {
                                defer.resolve(response);
                            }, function (err) {
                                NotificationSrv.error('DashboardViewCtrl', err.data, err.status);
                                defer.reject(err);
                            });

                        return defer.promise;
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.settings', {
                url: 'settings',
                templateUrl: 'views/partials/personal-settings.html',
                controller: 'SettingsCtrl',
                title: 'Personal settings',
                resolve: {
                    currentUser: function ($q, $state, $timeout, AuthenticationSrv, NotificationSrv) {
                        var deferred = $q.defer();

                        AuthenticationSrv.current()
                            .then(function (userData) {
                                return deferred.resolve(userData);
                            })
                            .catch(function (err) {
                                NotificationSrv.error('SettingsCtrl', err.data, err.status);

                                return deferred.reject(err);
                            });

                        return deferred.promise;
                    },
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                }
            })
            .state('app.administration', {
                abstract: true,
                url: 'administration',
                template: '<ui-view/>'
            })
            .state('app.administration.platform', {
                url: '/platform',
                templateUrl: 'views/partials/admin/platform/status.html',
                controller: 'PlatformStatusCtrl',
                controllerAs: '$vm',
                title: 'Platform administration',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                },
                guard: {
                    permissions: ['managePlatform']
                }
            })
            .state('app.administration.profiles', {
                url: '/profiles',
                templateUrl: 'views/partials/admin/profile/list.html',
                controller: 'ProfileListCtrl',
                controllerAs: '$vm',
                title: 'Profiles administration',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                },
                guard: {
                    permissions: ['manageProfile']
                }
            })
            .state('app.administration.taxonomies', {
                url: '/taxonomies',
                templateUrl: 'views/partials/admin/taxonomy/list.html',
                controller: 'TaxonomyListCtrl',
                controllerAs: '$vm',
                title: 'Taxonomies administration',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                },
                guard: {
                    permissions: ['manageTaxonomy']
                }
            })
            .state('app.administration.attackPatterns', {
                url: '/attack-patterns',
                templateUrl: 'views/partials/admin/attack/list.html',
                controller: 'AttackPatternListCtrl',
                controllerAs: '$vm',
                title: 'ATT&CK patterns administration',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                }
                // guard: {
                //     permissions: ['manageTaxonomy']
                // }
            })
            .state('app.administration.organisations', {
                url: '/organisations',
                templateUrl: 'views/partials/admin/organisation/list.html',
                controller: 'OrgListCtrl',
                controllerAs: '$vm',
                title: 'Organisations administration',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    }
                },
                guard: {
                    permissions: ['manageOrganisation']
                }
            })
            .state('app.administration.organisations-details', {
                url: '/organisations/{organisation}/details',
                templateUrl: 'views/partials/admin/organisation/details.html',
                controller: 'OrgDetailsCtrl',
                controllerAs: '$vm',
                resolve: {
                    organisation: function ($stateParams, OrganisationSrv) {
                        return OrganisationSrv.get($stateParams.organisation);
                    },
                    fields: function (CustomFieldsSrv) {
                        return CustomFieldsSrv.all();
                    },
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    },
                    uiConfig: function ($q, UiSettingsSrv) {
                        return UiSettingsSrv.all(true);
                    }
                },
                guard: {
                    permissions: ['manageOrganisation', 'manageUser', 'manageCaseTemplate']
                }
            })
            .state('app.administration.analyzer-templates', {
                url: '/analyzer-templates',
                templateUrl: 'views/partials/admin/analyzer-templates.html',
                controller: 'AdminAnalyzerTemplatesCtrl',
                controllerAs: 'vm',
                title: 'Analyzer templates administration',
                resolve: {
                    appConfig: function ($q, VersionSrv) {
                        var defer = $q.defer();

                        VersionSrv.get()
                            .then(function (config) {
                                // Check if Cortex is enabled
                                if (VersionSrv.hasCortexConnector()) {
                                    defer.resolve(config);
                                } else {
                                    defer.reject();
                                }
                            });

                        return defer.promise;
                    },
                },
                guard: {
                    permissions: ['manageAnalyzerTemplate']
                }
            })
            .state('app.administration.custom-fields', {
                url: '/custom-fields',
                templateUrl: 'views/partials/admin/custom-fields.html',
                controller: 'AdminCustomFieldsCtrl',
                controllerAs: '$vm',
                title: 'Custom fields administration',
                guard: {
                    permissions: ['manageCustomField']
                }
            })
            .state('app.administration.observables', {
                url: '/observables',
                templateUrl: 'views/partials/admin/observables.html',
                controller: 'AdminObservablesCtrl',
                controllerAs: '$vm',
                title: 'Observable administration',
                resolve: {
                    types: function (ObservableTypeSrv, $q) {
                        return ObservableTypeSrv.list()
                            .then(function (response) {
                                return $q.resolve(response.data);
                            });
                    }
                },
                guard: {
                    permissions: ['manageObservableTemplate']
                }
            })
            .state('app.case', {
                abstract: true,
                url: 'case/{caseId}',
                templateUrl: 'views/app.case.html',
                controller: 'CaseMainCtrl',
                title: 'Case',
                resolve: {
                    caze: function ($q, $stateParams, CaseSrv, NotificationSrv) {
                        var deferred = $q.defer();

                        CaseSrv.getById($stateParams.caseId, true)
                            .then(function (data) {
                                deferred.resolve(data);
                            }).catch(function (response) {
                                deferred.reject(response);
                                NotificationSrv.error('CaseMainCtrl', response.data, response.status);
                            });

                        return deferred.promise;
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.details', {
                url: '/details',
                templateUrl: 'views/partials/case/case.details.html',
                controller: 'CaseDetailsCtrl',
                data: {
                    tab: 'details'
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.tasks', {
                url: '/tasks',
                templateUrl: 'views/partials/case/case.tasks.html',
                controller: 'CaseTasksCtrl',
                data: {
                    tab: 'tasks'
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.links', {
                url: '/links',
                templateUrl: 'views/partials/case/case.links.html',
                controller: 'CaseLinksCtrl',
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.sharing', {
                url: '/sharing',
                templateUrl: 'views/partials/case/case.sharing.html',
                controller: 'CaseSharingCtrl',
                controllerAs: '$vm',
                resolve: {
                    organisations: function (AuthenticationSrv, OrganisationSrv) {
                        return AuthenticationSrv.current()
                            .then(function (user) {
                                return OrganisationSrv.links(user.organisation);
                            })
                            .then(function (response) {
                                return _.map(response, function (item) {
                                    return _.pick(item, 'name', 'description');
                                });
                            });
                    },
                    profiles: function (ProfileSrv) {
                        return ProfileSrv.list()
                            .then(function (response) {

                                return _.map(_.filter(response.data, function (item) {
                                    return !item.isAdmin;
                                }), 'name');
                            });
                    },
                    shares: function (CaseSrv, $stateParams) {
                        return CaseSrv.getShares($stateParams.caseId)
                            .then(function (response) {
                                return response.data;
                            });
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.alerts', {
                url: '/alerts',
                templateUrl: 'views/partials/case/case.alerts.html',
                controller: 'CaseAlertsCtrl',
                resolve: {
                    alerts: function ($stateParams, CaseSrv) {
                        return CaseSrv.alerts($stateParams.caseId);
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.tasks-item', {
                url: '/tasks/{itemId}',
                templateUrl: 'views/partials/case/case.tasks.item.html',
                controller: 'CaseTasksItemCtrl',
                resolve: {
                    task: function ($q, $stateParams, CaseTaskSrv, NotificationSrv) {
                        var deferred = $q.defer();

                        CaseTaskSrv.getById($stateParams.itemId)
                            .then(function (data) {
                                deferred.resolve(data);
                            })
                            .catch(function (response) {
                                deferred.reject(response);
                                NotificationSrv.error('taskDetails', response.data, response.status);
                            });

                        return deferred.promise;
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.observables', {
                url: '/observables',
                templateUrl: 'views/partials/case/case.observables.html',
                controller: 'CaseObservablesCtrl',
                data: {
                    tab: 'observables'
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.observables-item', {
                url: '/observables/{itemId}',
                templateUrl: 'views/partials/case/case.observables.item.html',
                controller: 'CaseObservablesItemCtrl',
                resolve: {
                    appConfig: function (VersionSrv) {
                        return VersionSrv.get();
                    },
                    artifact: function ($q, $stateParams, CaseArtifactSrv, NotificationSrv) {
                        var deferred = $q.defer();

                        CaseArtifactSrv.api().get({
                            'artifactId': $stateParams.itemId
                        }).$promise.then(function (data) {
                            deferred.resolve(data);
                        }).catch(function (response) {
                            deferred.reject(response);
                            NotificationSrv.error('Observable Details', response.data, response.status);
                        });

                        return deferred.promise;
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.case.procedures', {
                url: '/procedures',
                templateUrl: 'views/partials/case/case.procedures.html',
                controller: 'CaseProceduresCtrl',
                controllerAs: '$vm',
                data: {
                    tab: 'procedures'
                },
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.alert-list', {
                url: 'alert/list',
                templateUrl: 'views/partials/alert/list.html',
                controller: 'AlertListCtrl',
                controllerAs: '$vm',
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.dashboards', {
                url: 'dashboards',
                templateUrl: 'views/partials/dashboard/list.html',
                controller: 'DashboardsCtrl',
                controllerAs: '$vm',
                guard: {
                    isSuperAdmin: false
                }
            })
            .state('app.dashboards-view', {
                url: 'dashboards/{id}',
                templateUrl: 'views/partials/dashboard/view.html',
                controller: 'DashboardViewCtrl',
                controllerAs: '$vm',
                resolve: {
                    dashboard: function (NotificationSrv, DashboardSrv, $stateParams, $q) {
                        var defer = $q.defer();

                        DashboardSrv.get($stateParams.id)
                            .then(function (response) {
                                defer.resolve(response.data);
                            }, function (err) {
                                NotificationSrv.error('DashboardViewCtrl', err.data, err.status);
                                defer.reject(err);
                            });

                        return defer.promise;
                    },
                    metadata: function ($q, DashboardSrv, NotificationSrv) {
                        var defer = $q.defer();

                        DashboardSrv.getMetadata()
                            .then(function (response) {
                                defer.resolve(response);
                            }, function (err) {
                                NotificationSrv.error('DashboardViewCtrl', err.data, err.status);
                                defer.reject(err);
                            });

                        return defer.promise;
                    }
                },
                guard: {
                    isSuperAdmin: false
                }
            });
    })
    .config(function ($httpProvider) {
        'use strict';

        $httpProvider.defaults.xsrfCookieName = 'THEHIVE-XSRF-TOKEN';
        $httpProvider.defaults.xsrfHeaderName = 'X-THEHIVE-XSRF-TOKEN';
        $httpProvider.interceptors.push(function ($rootScope, $q) {
            var isApiCall = function (url) {
                return url && url.startsWith('./api') && !url.startsWith('./api/stream');
            };

            return {
                request: function (config) {
                    if (isApiCall(config.url)) {
                        $rootScope.async += 1;
                    }
                    return config;
                },
                response: function (response) {
                    if (isApiCall(response.config.url)) {
                        $rootScope.async -= 1;
                    }
                    return response;
                },
                responseError: function (rejection) {
                    if (isApiCall(rejection.config.url)) {
                        $rootScope.async -= 1;
                    }
                    return $q.reject(rejection);
                }
            };
        });
    })
    .config(function (localStorageServiceProvider) {
        'use strict';

        localStorageServiceProvider
            .setPrefix('th4')
            .setStorageType('localStorage')
            .setNotify(false, false);
    })
    .config(function (NotificationProvider) {
        'use strict';

        NotificationProvider.setOptions({
            delay: 10000,
            startTop: 20,
            startRight: 10,
            verticalSpacing: 20,
            horizontalSpacing: 20,
            positionX: 'left',
            positionY: 'bottom',
            maxCount: 5
        });
    })
    .config(function ($provide, markedProvider, hljsServiceProvider) {
        'use strict';

        markedProvider.setOptions({
            gfm: true,
            tables: true,
            sanitize: true,
            highlight: function (code, lang) {
                if (lang) {
                    return hljs.highlight(lang, code, true).value;
                } else {
                    return hljs.highlightAuto(code).value;
                }
            }
        });

        // highlight config
        hljsServiceProvider.setOptions({
            tabReplace: '    '
        });

        // Decorate the marked service to allow generating links with _target="blank"
        $provide.decorator('marked', [
            '$delegate',
            function markedDecorator($delegate) {
                // Credits: https://github.com/markedjs/marked/issues/655#issuecomment-383226346
                var defaults = markedProvider.defaults;

                var renderer = defaults.renderer;
                var linkRenderer = _.wrap(renderer.link, function (originalLink, href, title, text) {
                    var html = originalLink.call(renderer, href, title, text);
                    return html.replace(/^<a /, '<a target="_blank" rel="nofollow" ');
                });

                // Customize the link renderer
                defaults.renderer.link = linkRenderer;

                // Patch the marked instance
                $delegate.setOptions(defaults);

                return $delegate;
            }
        ]);
    })
    .run(function ($rootScope, $state, $q, AuthenticationSrv) {
        'use strict';
        $rootScope.async = 0;

        // Handle route guards
        $rootScope.$on('$stateChangeSuccess', function (event, toState/*, toParams*/) {

            if (!toState.guard) {
                return;
            }

            // Try Permissions
            if (toState.guard.permissions !== undefined) {
                var permissions = toState.guard.permissions;

                if (permissions && !AuthenticationSrv.hasPermission(permissions)) {
                    event.preventDefault();
                    $state.go('app.index');
                }
            }

            // Try isSupperAdmin
            if (toState.guard.isSuperAdmin !== undefined && AuthenticationSrv.isSuperAdmin() !== toState.guard.isSuperAdmin) {
                event.preventDefault();
                $state.go('app.index');
            }
        });

        // Update page title based on the route
        $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
            if (_.isFunction(toState.title)) {
                $rootScope.title = toState.title(toParams);
            } else {
                $rootScope.title = toState.title;
            }
        });

        // Handle 401 errors when navigating to a route
        $rootScope.$on('$stateChangeError', function (event, toState, toParams, fromState, fromParams, error) {
            if (error && error.status && error.status === 401) {
                event.preventDefault();
                $state.go('login');
            }
        });
    })
    .constant('UrlParser', url);
