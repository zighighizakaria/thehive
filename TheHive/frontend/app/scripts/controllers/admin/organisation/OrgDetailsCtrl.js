(function() {
    'use strict';

    angular.module('theHiveControllers').controller('OrgDetailsCtrl',
        function($scope, FilteringSrv, PaginatedQuerySrv, NotificationSrv, UserSrv, organisation, fields, appConfig, uiConfig) {
            var self = this;

            this.uiConfig = uiConfig;
            this.org = organisation;
            this.fields = fields;
            this.canChangeMfa = appConfig.config.capabilities.indexOf('mfa') !== -1;
            this.canSetPass = appConfig.config.capabilities.indexOf('setPassword') !== -1;

            this.getUserInfo = UserSrv.getCache;

            this.$onInit = function() {
                self.filtering = new FilteringSrv('user', 'user.list', {
                    version: 'v1',
                    defaults: {
                        showFilters: true,
                        showStats: false,
                        pageSize: 15,
                        sort: ['+login']
                    },
                    defaultFilter: []
                });

                self.filtering.initContext(self.org.name)
                    .then(function() {
                        self.loadUsers();

                        $scope.$watch('$vm.users.pageSize', function (newValue) {
                            self.filtering.setPageSize(newValue);
                        });
                    });
            };

            this.loadUsers = function() {

                self.users = new PaginatedQuerySrv({
                    name: 'organisation-users',
                    version: 'v1',
                    skipStream: true,
                    sort: self.filtering.context.sort,
                    loadAll: false,
                    pageSize: self.filtering.context.pageSize,
                    pageOptions: {organisation: self.org.name},
                    filter: this.filtering.buildQuery(),
                    operations: [{
                            '_name': 'getOrganisation',
                            'idOrName': self.org.name
                        },
                        {
                            '_name': 'users'
                        }
                    ],
                    onFailure: function(err) {
                        if(err && err.status === 400) {
                            self.filtering.resetContext();
                            self.loadUsers();
                        }
                    }
                });
            };

            this.showUserDialog = function(user) {
                UserSrv.openModal(user, self.org.name)
                    .then(function() {
                        self.loadUsers();
                    })
                    .catch(function(err) {
                        if (err && !_.isString(err)) {
                            NotificationSrv.error('OrgDetailsCtrl', err.data, err.status);
                        }
                    });
            };

            // Filtering
            this.toggleFilters = function () {
                this.filtering.toggleFilters();
            };

            this.filter = function () {
                self.filtering.filter().then(this.applyFilters);
            };

            this.clearFilters = function () {
                this.filtering.clearFilters()
                    .then(self.search);
            };

            this.removeFilter = function (index) {
                self.filtering.removeFilter(index)
                    .then(self.search);
            };

            this.search = function () {
                self.loadUsers();
                self.filtering.storeContext();
            };
            this.addFilterValue = function (field, value) {
                this.filtering.addFilterValue(field, value);
                this.search();
            };

            this.filterBy = function(field, value) {
                self.filtering.clearFilters()
                    .then(function(){
                        self.addFilterValue(field, value);
                    });
            };

            this.sortByField = function(field) {
                var context = this.filtering.context;
                var currentSort = Array.isArray(context.sort) ? context.sort[0] : context.sort;
                var sort = null;

                if(currentSort && currentSort.substr(1) !== field) {
                    sort = ['+' + field];
                } else {
                    sort = [(currentSort === '+' + field) ? '-'+field : '+'+field];
                }

                self.users.sort = sort;
                self.users.update();
                self.filtering.setSort(sort);
            };

        });
})();
