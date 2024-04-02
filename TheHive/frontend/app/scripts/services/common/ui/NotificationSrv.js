(function() {
    'use strict';
    angular.module('theHiveServices')
        .factory('NotificationSrv', function($state, HtmlSanitizer, Notification) {

            function log(message, type) {
                Notification[type || 'error'](HtmlSanitizer.sanitize(message));
            }

            return {
                'success': function(message) {
                    log(message, 'success');
                },
                'log': log,
                'error': function(moduleName, data, status) {
                    if (status === 401) {
                        $state.go('login');
                    } else if (status === 520) {
                        $state.go('maintenance');
                    } else if (angular.isString(data) && data !== '') {
                        log(moduleName + ': ' + data, 'error');
                    } else if (angular.isObject(data)) {
                        log(moduleName + ': ' + data.message, 'error');
                    }
                }
            };
        });
})();
