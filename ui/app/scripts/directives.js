/*
 * Copyright 2012-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global glowroot, angular, Glowroot, $, Spinner, moment */

glowroot.factory('gtButtonGroupControllerFactory', [
  '$q',
  function ($q) {
    return {
      create: function (element, noSpinner) {
        var $element = $(element);
        var alreadyExecuting = false;
        return {
          onClick: function (fn) {
            // handle crazy user clicking on the button
            if (alreadyExecuting) {
              return;
            }
            var $buttonMessage = $element.find('.gt-button-message');
            var $buttonSpinner = $element.find('.gt-button-spinner');
            // in case button is clicked again before message fades out
            $buttonMessage.addClass('d-none');
            var spinner;
            if (!noSpinner) {
              spinner = Glowroot.showSpinner($buttonSpinner);
            }

            var deferred = $q.defer();
            deferred.promise.then(function (message) {
              if (spinner) {
                spinner.stop();
              }
              // if message is undefined (e.g. no explicit success message), need to pass empty string,
              // otherwise it won't overwrite old error message in $buttonMessage if there is one
              $buttonMessage.text(message || '');
              $buttonMessage.removeClass('gt-button-message-error');
              $buttonMessage.addClass('gt-button-message-success');
              Glowroot.showAndFadeSuccessMessage($buttonMessage);
              alreadyExecuting = false;
            }, function (message) {
              if (spinner) {
                spinner.stop();
              }
              Glowroot.cancelFadeSuccessMessage($buttonMessage);
              $buttonMessage.text(message);
              $buttonMessage.removeClass('gt-button-message-success');
              $buttonMessage.addClass('gt-button-message-error');
              $buttonMessage.removeClass('d-none');
              alreadyExecuting = false;
            });

            alreadyExecuting = true;
            fn({deferred: deferred});
          }
        };
      }
    };
  }
]);

glowroot.directive('gtButtonGroup', [
  'gtButtonGroupControllerFactory',
  function (gtButtonGroupControllerFactory) {
    return {
      scope: {},
      transclude: true,
      template: ''
          + '<div class="clearfix">'
          + '  <span ng-transclude style="margin-right: 15px;"></span>'
          + '  <div class="gt-button-spinner d-none"></div>'
          + '  <div class="gt-button-message d-none" style="padding-top: 4px;"></div>'
          + '</div>',
      controller: [
        '$element',
        function ($element) {
          var gtButtonGroup = gtButtonGroupControllerFactory.create($element);
          this.onClick = gtButtonGroup.onClick;
        }
      ]
    };
  }
]);

glowroot.directive('gtButton', [
  'gtButtonGroupControllerFactory',
  'modals',
  function (gtButtonGroupControllerFactory, modals) {
    return {
      scope: {
        gtLabel: '@',
        gtClick: '&',
        gtBtnClass: '@',
        gtDisabled: '&',
        gtNoSpinner: '@',
        gtConfirmHeader: '@',
        gtConfirmBody: '@',
        gtValidateForm: '='
      },
      templateUrl: 'template/gt-button.html',
      require: '^?gtButtonGroup',
      link: function (scope, iElement, iAttrs, gtButtonGroup) {
        if (!gtButtonGroup) {
          scope.noGroup = true;
          gtButtonGroup = gtButtonGroupControllerFactory.create(iElement, scope.gtNoSpinner);
        }
        scope.onClick = function () {
          if (scope.gtValidateForm) {
            if (scope.gtValidateForm.$invalid) {
              scope.gtValidateForm.$$element.addClass('was-validated');
              gtButtonGroup.onClick(function (args) {
                args.deferred.reject('See validation error(s) above');
              });
              return;
            } else {
              scope.gtValidateForm.$$element.removeClass('was-validated');
              // and proceed
            }
          }
          if (scope.gtConfirmHeader) {
            var $modal = $('#confirmationModal');
            $modal.find('.modal-title').text(scope.gtConfirmHeader);
            $modal.find('.modal-body p').text(scope.gtConfirmBody);
            modals.display('#confirmationModal', true);
            $('#confirmationModalButton').off('click');
            $('#confirmationModalButton').on('click', function () {
              scope.$apply(function () {
                $('#confirmationModal').modal('hide');
                gtButtonGroup.onClick(scope.gtClick);
              });
            });
          } else {
            gtButtonGroup.onClick(scope.gtClick);
          }
        };
      }
    };
  }
]);

glowroot.directive('gtFormGroup', [
  'conversions',
  function (conversions) {
    return {
      scope: {
        gtType: '@',
        gtLabel: '@',
        gtCheckboxLabel: '@',
        gtModel: '=',
        gtWidth: '@',
        gtAddon: '@',
        // gtPattern accepts string binding for inline patterns and RegExp binding for scope expressions
        // (same as ngPattern on angular input directive)
        gtPattern: '@',
        gtRequired: '&',
        gtDisabled: '&',
        gtPlaceholder: '@',
        gtNumber: '&',
        gtRows: '@',
        gtColClass1: '@',
        gtColClass2: '@'
      },
      transclude: true,
      require: '^form',
      templateUrl: 'template/gt-form-group.html',
      link: function (scope, iElement, iAttrs, formCtrl) {
        scope.formCtrl = formCtrl;
        // just need a unique id
        scope.gtId = scope.$id;
        if (!scope.gtType) {
          // default
          scope.gtType = 'text';
        }
        if (scope.gtType === 'codemirror') {
          scope.codeMirrorOpts = {
            indentUnit: 4,
            lineNumbers: true,
            matchBrackets: true,
            viewportMargin: Infinity,
            mode: 'text/x-java'
          };
          if (scope.gtDisabled()) {
            scope.codeMirrorOpts.readOnly = true;
          }
        }
        scope.$watch('gtModel', function (newValue) {
          // conditional prevents the '.' from being automatically deleted when user deletes the '3' in '2.3'
          if (conversions.toNumber(scope.ngModel) !== newValue) {
            scope.ngModel = newValue;
          }
        });
        scope.$watch('ngModel', function (newValue) {
          if (scope.gtNumber()) {
            scope.gtModel = conversions.toNumber(newValue);
          } else {
            scope.gtModel = newValue;
          }
        });
        scope.$watch('gtPattern', function (newValue) {
          if (newValue) {
            var match = newValue.match(/^\/(.*)\/$/);
            if (match) {
              scope.ngPattern = new RegExp(match[1]);
            } else {
              scope.ngPattern = scope.$parent.$eval(newValue);
            }
          } else {
            // ngPattern doesn't understand falsy values (maybe it should?)
            // so just pass a pattern that will match everything
            scope.ngPattern = /.?/;
          }
        });
      }
    };
  }
]);

glowroot.directive('gtInputGroupDropdown', function () {
  return {
    scope: {
      gtModel: '=',
      gtItems: '&',
      gtClass: '@'
    },
    templateUrl: 'template/gt-input-group-dropdown.html',
    // replace is needed in order to not mess up bootstrap css hierarchical selectors
    replace: true,
    link: function (scope) {
      // update display when model changes
      scope.$watch('gtModel', function (newGtModel) {
        angular.forEach(scope.gtItems(), function (item) {
          if (item.value === newGtModel) {
            scope.gtDisplay = item.display;
          }
        });
      });
    }
  };
});

glowroot.directive('gtNavbarItem', [
  function () {
    return {
      scope: {
        gtDisplay: '@',
        gtItemName: '@',
        gtUrl: '@',
        gtClick: '&'
      },
      // replace is needed in order to not mess up bootstrap css hierarchical selectors
      replace: true,
      transclude: true,
      templateUrl: 'template/gt-navbar-item.html',
      link: function (scope) {
        scope.isActive = function () {
          return scope.$parent.activeNavbarItem === scope.gtItemName;
        };
      }
    };
  }
]);

glowroot.directive('gtSidebarItem', [
  '$location',
  function ($location) {
    return {
      scope: {
        gtDisplay: '@',
        gtDisplayRight: '@',
        gtUrl: '@',
        gtActive: '&',
        gtSubActive: '&'
      },
      // replace is needed in order to not mess up bootstrap css hierarchical selectors
      replace: true,
      templateUrl: 'template/gt-sidebar-item.html',
      link: function (scope, iElement, iAttrs) {
        scope.isActive = function () {
          return iAttrs.gtActive ? scope.gtActive() : $location.path() === '/' + scope.gtUrl;
        };
        // isSubActive sidebar items are marked active, but when clicked will return to their parent
        scope.isSubActive = function () {
          return iAttrs.gtSubActive && scope.gtSubActive();
        };
      }
    };
  }
]);

glowroot.directive('gtDisplayWhitespace', function () {
  return {
    scope: {
      gtBind: '&'
    },
    link: function (scope, iElement) {
      var text = scope.gtBind();
      iElement.text(text);
      var html = iElement.html();
      html = html.replace('\n', '<em>\\n</em>')
          .replace('\r', '<em>\\r</em>')
          .replace('\t', '<em>\\t</em>');
      html = html.replace('</em><em>', '');
      iElement.html(html);
    }
  };
});

glowroot.directive('gtSpinner', function () {
  return function (scope, iElement, iAttrs) {
    var spinner;
    var timer;
    iElement.addClass('d-none');
    scope.$watch(iAttrs.gtShow,
        function (newValue) {
          if (newValue) {
            if (spinner) {
              return;
            }
            var left;
            if (iAttrs.gtSpinnerInline) {
              left = 10;
            }
            // z-index should be less than navbar (which is 1030)
            spinner = new Spinner({lines: 9, radius: 8, width: 5, left: left, zIndex: 1020});
            if (iAttrs.gtNoDelay) {
              iElement.removeClass('d-none');
              spinner.spin(iElement[0]);
            } else {
              // small delay so that if there is an immediate response the spinner doesn't blink
              //
              // must clear previous timer, e.g. in case multiple keystrokes are entered on typeahead
              // inside of 100 milliseconds
              clearTimeout(timer);
              timer = setTimeout(function () {
                iElement.removeClass('d-none');
                spinner.spin(iElement[0]);
              }, 100);
            }
          } else if (spinner) {
            clearTimeout(timer);
            iElement.addClass('d-none');
            spinner.stop();
            spinner = undefined;
          }
        });
  };
});

glowroot.directive('gtFormWithPrimaryButton', function () {
  return function (scope, iElement) {
    iElement.on('keypress', 'input', function (e) {
      if (e.which === 13) {
        // only click on button if it is enabled
        iElement.find('.btn-primary:enabled').first().click();
      }
    });
  };
});

glowroot.directive('gtFormAutofocusOnFirstInput', [
  '$timeout',
  function ($timeout) {
    return function (scope, iElement) {
      $timeout(function () {
        var selector = 'input:not(.gt-autofocus-ignore),select';
        var unregisterWatch = scope.$watch(function () {
          return iElement.find(selector).length && iElement.find('input,select').first().is(':visible');
        }, function (newValue) {
          if (newValue) {
            iElement.find(selector).first().focus();
            unregisterWatch();
          }
        });
      }, 100);
    };
  }
]);

glowroot.directive('gtSmartClick', function () {
  return {
    scope: {
      gtSmartClick: '&'
    },
    link: function (scope, iElement) {
      iElement.mousedown(function (e) {
        scope.mousedownPageX = e.pageX;
        scope.mousedownPageY = e.pageY;
      });
      iElement.click(function (event, keyboard) {
        if (!keyboard && (Math.abs(event.pageX - scope.mousedownPageX) > 5
            || Math.abs(event.pageY - scope.mousedownPageY) > 5)) {
          // not a simple single click, probably highlighting text
          return;
        }
        scope.$apply(function () {
          scope.gtSmartClick({$event: event});
        });
      });
    }
  };
});

glowroot.directive('gtSelectpicker', [
  '$timeout',
  function ($timeout) {
    return {
      scope: {
        gtModel: '=',
        gtTitle: '&'
      },
      link: function (scope, iElement) {
        // need to set title before initializing selectpicker in order to avoid flicker of 'None selected' text
        // when going back and forth between two different transaction types
        iElement.attr('title', scope.gtTitle());
        // set style outside of $timeout to avoid style flicker on loading
        iElement.selectpicker();
        scope.$watch('gtModel', function () {
          // only works inside of $timeout
          $timeout(function () {
            iElement.selectpicker('val', scope.gtModel);
          });
        });
        $timeout(function () {
          // refresh only works inside of $timeout
          iElement.selectpicker('refresh');
        });
        scope.$on('$destroy', function () {
          iElement.selectpicker('destroy');
        });
      }
    };
  }
]);

glowroot.directive('gtMultiselect', [
  function () {
    return {
      scope: {
        gtNoneSelectedText: '@'
      },
      link: function (scope, iElement) {
        iElement.multiselect({
          enableFiltering: true,
          enableCaseInsensitiveFiltering: true,
          filterPlaceholder: 'Filter',
          nonSelectedText: scope.gtNoneSelectedText,
          maxHeight: 400,
          numberDisplayed: 2,
          includeSelectAllOption: true,
          // mostly for bootstrap 4 compatibility,
          // and mostly copied from https://github.com/davidstutz/bootstrap-multiselect/pull/1050
          buttonClass: 'btn btn-secondary',
          buttonContainer: '<div class="dropdown" />',
          templates: {
            button: '<button type="button" class="multiselect dropdown-toggle" data-toggle="dropdown" data-flip="false">'
                + '<span class="multiselect-selected-text"></span>'
                + ' <b class="caret"></b>'
                + '</button>',
            ul: '<ul class="multiselect-container dropdown-menu p-1 m-0"></ul>',
            filter: '<li class="multiselect-item multiselect-filter">'
                + '<input class="form-control multiselect-search" type="text" />'
                + '</li>',
            filterClearBtn: '',
            li: '<li>'
                + '<a tabIndex="0" class="dropdown-item" style="padding: 2px 0;">'
                + '<label class="custom-control custom-checkbox" style="padding-left: 36px;">'
                + '<div class="custom-control-label"></div>'
                + '</label>'
                + '</a>'
                + '</li>',
          },
          optionLabel: function (element) {
            return $(element).data('val').indentedDisplay;
          },
          optionFullText: function (element) {
            return $(element).data('val').display;
          },
          onDropdownShown: function () {
            iElement.parent().find('.multiselect-search').focus();
          },
          onDropdownHidden: function () {
            iElement.removeClass('ng-untouched');
            iElement.addClass('ng-touched');
          }
        });
        scope.$on('$destroy', function () {
          iElement.multiselect('destroy');
        });
      }
    };
  }
]);

glowroot.directive('gtDatePicker', [
  '$timeout',
  function ($timeout) {
    return {
      scope: {
        gtModel: '=',
        gtId: '@',
        gtForm: '='
      },
      templateUrl: 'template/gt-date-picker.html',
      link: function (scope, iElement) {
        var dateElement = iElement.find('.date');
        dateElement.datetimepicker({
          format: 'L',
          keepInvalid: true
        });
        scope.$watch('gtModel', function (newValue) {
          dateElement.datetimepicker('date', moment(newValue));
        });
        var inputElement = iElement.find('input');
        dateElement.on('change.datetimepicker', function (event) {
          $timeout(function () {
            if (event.date && event.date.isValid()) {
              scope.gtModel = event.date.valueOf();
              scope.gtForm['input_' + scope.$id].$setValidity('required', true);
              scope.gtForm['input_' + scope.$id].$setValidity('date', true);
            } else {
              scope.gtForm['input_' + scope.$id].$setValidity('required', !!inputElement.val());
              scope.gtForm['input_' + scope.$id].$setValidity('date', !event.date);
            }
          });
        });
      }
    };
  }
]);

glowroot.directive('gtTimerDisplay', function () {
  return {
    scope: {
      heading: '@',
      flattenedTimers: '=',
      treeTimers: '=',
      transactionCount: '='
    },
    templateUrl: 'template/gt-timer-display.html',
    link: function (scope) {

      function buildTotalNanosList(timers) {
        var totalNanosList = [];
        angular.forEach(timers, function (timer) {
          totalNanosList.push(timer.totalNanos);
        });
        totalNanosList.sort(function (a, b) {
          return b - a;
        });
        return totalNanosList;
      }

      var flattenedTotalNanosList = [];
      var treeTotalNanosList = [];

      scope.limit = 10;

      function updateLimit() {
        scope.ftShowMore = scope.limit < scope.flattenedTimers.length;
        scope.ttShowMore = scope.limit < scope.treeTimers.length;
        scope.showLess = scope.limit !== 10;

        function updateLimitOne(timers, totalNanosList) {
          var limit = Math.min(scope.limit, totalNanosList.length);
          var thresholdNanos = totalNanosList[limit - 1];
          var count = 0;
          angular.forEach(timers, function (timer) {
            // count is to handle multiple timers equal to the threshold
            timer.show = timer.totalNanos >= thresholdNanos && count++ < scope.limit;
          });
        }

        updateLimitOne(scope.flattenedTimers, flattenedTotalNanosList);
        updateLimitOne(scope.treeTimers, treeTotalNanosList);
      }

      scope.$watch('flattenedTimers', function () {
        // the timer list changes each time the chart is refreshed
        flattenedTotalNanosList = buildTotalNanosList(scope.flattenedTimers);
        treeTotalNanosList = buildTotalNanosList(scope.treeTimers);
        updateLimit();
      });

      scope.clickShowMore = function () {
        scope.limit *= 2;
        updateLimit();
      };

      scope.clickShowLessFT = function () {
        scope.limit /= 2;
        while (scope.limit >= scope.flattenedTimers.length) {
          // show less should always leave displayed list less than full list
          scope.limit /= 2;
        }
        updateLimit();
      };

      scope.clickShowLessTT = function () {
        scope.limit /= 2;
        updateLimit();
      };

      scope.clickShowAll = function () {
        while (scope.limit < scope.treeTimers.length) {
          scope.limit *= 2;
        }
        updateLimit();
      };
    }
  };
});


glowroot.directive('gtThreadStats', function () {
  return {
    scope: {
      threadStats: '=',
      transactionCount: '='
    },
    templateUrl: 'template/gt-thread-stats.html'
  };
});
