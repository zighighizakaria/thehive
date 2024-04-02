(function() {
    'use strict';
    angular.module('theHiveDirectives').directive('dashboardBar', function($http, $state, DashboardSrv, NotificationSrv) {
        return {
            restrict: 'E',
            scope: {
                filter: '=?',
                options: '=',
                entity: '=',
                autoload: '=',
                mode: '=',
                refreshOn: '@',
                resizeOn: '@',
                metadata: '='
            },
            template: '<c3 chart="chart" resize-on="{{resizeOn}}" error="error" on-save-csv="getCsv()"></c3>',
            link: function(scope) {
                scope.error = false;
                scope.chart = {};

                scope.intervals = DashboardSrv.timeIntervals;
                scope.interval = scope.intervals[2];

                scope.load = function() {
                    if(!scope.entity) {
                        scope.error = true;
                        return;
                    }

                    scope.prepareSeriesNames = function() {
                        if(!scope.options.field) {
                            return {};
                        }

                        var field = scope.entity.attributes[scope.options.field];

                        if(field.values.length === 0) {
                            // This is not an enumerated field
                            // Labels and colors customization is not available
                            return {};
                        }

                        var names = scope.options.names || {};

                        _.each(field.values, function(val, index) {
                            if(!names[val]) {
                                names[val] = field.labels[index] || val;
                            }
                        });

                        return names;
                    };

                    var query = DashboardSrv.buildChartQuery(scope.filter, scope.options.query);

                    var statsPromise = $http.post('./api' + scope.entity.path + '/_stats', {
                        query: query,
                        stats: [{
                            _agg: 'time',
                            _fields: [scope.options.dateField],
                            _interval: scope.options.interval || scope.interval.code,
                            _select: [{
                                _agg: 'field',
                                _field: scope.options.field,
                                _select: [{
                                    _agg: 'count'
                                }]
                            }]
                        }]
                    });

                    statsPromise.then(function(response) {
                        scope.error = false;
                        var len = _.keys(response.data).length,
                            data = {_date: (new Array(len)).fill(0)};

                        var rawData = {};
                        _.each(response.data, function(value, key) {
                            rawData[key] = value[scope.options.dateField];
                        });

                        _.each(rawData, function(value) {
                            _.each(_.keys(value), function(key){
                                data[key] = (new Array(len)).fill(0);
                            });
                        });

                        var i = 0;
                        var orderedDates = _.sortBy(_.keys(rawData));

                        _.each(orderedDates, function(key) {
                            var value = rawData[key];
                            data._date[i] = moment(key * 1).format('YYYY-MM-DD');

                            _.each(_.keys(value), function(item) {
                                data[item][i] = value[item].count;
                            });

                            i++;
                        });


                        scope.options.names = scope.prepareSeriesNames();
                        scope.colors = {};

                        scope.data = data;

                        var chart = {
                            data: {
                                x: '_date',
                                json: scope.data,
                                type: 'bar',
                                names: scope.options.names || {},
                                colors: scope.options.colors || {},
                                groups: scope.options.stacked === true ? [_.without(_.keys(data), '_date')] : []
                            },
                            bar: {
                                width: {
                                    ratio: 1 - Math.exp(-len/20)
                                }
                            },
                            axis: {
                                x: {
                                    type: 'timeseries',
                                    tick: {
                                        format: '%Y-%m-%d',
                                        rotate: 90,
                                        height: 50
                                    }
                                }
                            },
                            zoom: {
                                enabled: scope.options.zoom || false
                            }
                        };

                        scope.chart = chart;
                    }, function(err) {
                        scope.error = true;
                        NotificationSrv.error('dashboardBar', 'Failed to fetch data, please edit the widget definition', err.status);
                    });
                };

                scope.getCsv = function() {
                    var dates = scope.data._date;
                    var keys = _.keys(scope.data);
                    var headers = _.extend({_date: 'Date'}, scope.names);

                    var csv = [{data: _.map(keys, function(key){
                        return headers[key] || key;
                    }).join(';')}];

                    var row = [];
                    for(var i=0; i<dates.length; i++) {
                        row = _.map(keys, function(key) {
                            return scope.data[key][i];
                        });

                        csv.push({data: row.join(';')});
                    }

                    return csv;
                };

                if (scope.autoload === true) {
                    scope.load();
                }

                if (!_.isEmpty(scope.refreshOn)) {
                    scope.$on(scope.refreshOn, function(event, filter) {
                        scope.filter = filter;
                        scope.load();
                    });
                }
            }
        };
    });
})();
