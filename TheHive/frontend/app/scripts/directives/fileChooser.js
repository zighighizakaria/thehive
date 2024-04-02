(function() {
    'use strict';
    angular.module('theHiveDirectives').directive('fileChooser', function() {
        return {
            'restrict': 'A',
            'link': function(scope, element) {
                var dropzone;
                element.addClass('dropzone');
                var template = element[0].innerHTML;
                $(element[0].children[0]).remove();
                // create a Dropzone for the element with the given options
                dropzone = new Dropzone(element[0], {
                    'url': 'dummy',
                    'autoProcessQueue': false,
                    'maxFiles': 1,
                    'createImageThumbnails': (angular.isString(scope.preview)) ? (scope.preview === 'true') : true,
                    'acceptedFiles': (angular.isString(scope.accept)) ? scope.accept : undefined,
                    'previewTemplate': template
                });

                dropzone.on('addedfile', function(file) {
                    scope.$apply(function() {
                        scope.filemodel = file;
                    });
                });
                dropzone.on('removedfile', function() {
                    var files = this.files;

                    if(files && files.length !== 1) {
                        setTimeout(function() {
                            scope.$apply(function() {
                                delete scope.filemodel;
                            });
                        }, 0);
                    } else {
                        scope.$apply(function() {
                            scope.filemodel = files[0];
                        });
                    }
                });
                dropzone.on('maxfilesexceeded', function(file) {
                    this.removeFile(file);
                });
                if (angular.isDefined(scope.control)) {
                    scope.control.removeAllFiles = function() {
                        dropzone.removeAllFiles();
                    };
                }
                //  else {
                //     console.log('Don\'t add removeAllFiles function as control object is not defined');
                // }
            },
            'templateUrl': 'views/directives/dropzone.html',
            'scope': {
                'filemodel': '=',
                'control': '=',
                'preview': '@?',
                'accept': '@?'
            }
        };
    });

})();
