/*
 * Copyright 2012-2015 the original author or authors.
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

/* global glowroot, angular, Glowroot, $, Spinner */

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
            $buttonMessage.addClass('hide');
            var spinner;
            if (!noSpinner) {
              spinner = Glowroot.showSpinner($buttonSpinner);
            }

            var deferred = $q.defer();
            deferred.promise.then(function (success) {
              if (spinner) {
                spinner.stop();
              }
              // if success is undefined (e.g. no explicit success message), need to pass empty string,
              // otherwise it won't overwrite old error message in $buttonMessage if there is one
              $buttonMessage.text(success || '');
              $buttonMessage.removeClass('gt-button-message-error');
              $buttonMessage.addClass('gt-button-message-success');
              Glowroot.showAndFadeSuccessMessage($buttonMessage);
              alreadyExecuting = false;
            }, function (error) {
              if (spinner) {
                spinner.stop();
              }
              $buttonMessage.text(error);
              $buttonMessage.removeClass('gt-button-message-success');
              $buttonMessage.addClass('gt-button-message-error');
              $buttonMessage.removeClass('hide');
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
      template: '' +
      '<div class="clearfix">' +
      '  <div ng-transclude style="float: left;"></div>' +
      '  <span class="gt-button-spinner hide" style="float: left;"></span>' +
        // this needs to be div, and it's child needs to be div, for formatting of multi-line messages
        // same as done in gt-button.html template
      '  <div style="overflow-x: hidden;">' +
      '    <div class="gt-button-message hide" style="padding-top: 5px;"></div>' +
      '  </div>' +
      '</div>',
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
  function (gtButtonGroupControllerFactory) {
    return {
      scope: {
        gtLabel: '@',
        gtClick: '&',
        gtShow: '&',
        gtBtnClass: '@',
        gtDisabled: '&',
        gtNoSpinner: '@'
      },
      templateUrl: function (tElement, tAttrs) {
        if (tAttrs.hasOwnProperty('gtButtonRightAligned')) {
          return 'template/gt-button-right-aligned.html';
        } else {
          return 'template/gt-button.html';
        }
      },
      require: '^?gtButtonGroup',
      link: function (scope, iElement, iAttrs, gtButtonGroup) {
        scope.ngShow = function () {
          return iAttrs.gtShow ? scope.gtShow() : true;
        };
        if (!gtButtonGroup) {
          scope.noGroup = true;
          gtButtonGroup = gtButtonGroupControllerFactory.create(iElement, scope.gtNoSpinner);
        }
        scope.onClick = function () {
          gtButtonGroup.onClick(scope.gtClick);
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
        gtNumber: '&',
        gtRows: '@'
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
        scope.$watch('gtModel', function (newValue) {
          scope.ngModel = newValue;
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

glowroot.directive('gtDatepicker', function () {
  return {
    scope: {
      gtModel: '=',
      gtId: '@',
      gtMaxWidth: '@'
    },
    template: '<input type="text" class="form-control gt-datepicker" ng-class="gtClass" id="{{gtId}}"' +
    ' ng-style="{\'max-width\': gtMaxWidth, \'height\': \'auto\'}">',
    link: function (scope, iElement, iAttrs) {
      if (!scope.gtMaxWidth) {
        scope.gtMaxWidth = '10em';
      }
      // TODO use bootstrap-datepicker momentjs backend when it's available and then use momentjs's
      // localized format 'moment.longDateFormat.L' both here and when parsing date
      // see https://github.com/eternicode/bootstrap-datepicker/issues/24
      var $input = $(iElement).find('input');
      $input.datepicker({format: 'mm/dd/yyyy', autoclose: true, todayHighlight: true});
      $input.datepicker('setDate', scope.gtModel);
      $input.on('changeDate', function (event) {
        scope.$apply(function () {
          scope.gtModel = event.date;
        });
      });
    }
  };
});

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
    link: function (scope, iElement, iAttrs) {
      // update display when model changes
      scope.$watch('gtModel', function (newGtModel) {
        angular.forEach(scope.gtItems(), function (item) {
          if (item.value === newGtModel) {
            scope.gtDisplay = item.display;
          }
        });
      });
      if (scope.gtClass) {
        scope.classes = 'input-group-btn ' + scope.gtClass;
      } else {
        scope.classes = 'input-group-btn';
      }
    }
  };
});

glowroot.directive('gtNavbarItem', [
  '$location',
  '$state',
  function ($location, $state) {
    return {
      scope: {
        gtDisplay: '@',
        gtItemName: '@',
        gtUrl: '@',
        gtShow: '&'
      },
      // replace is needed in order to not mess up bootstrap css hierarchical selectors
      replace: true,
      transclude: true,
      templateUrl: 'template/gt-navbar-item.html',
      link: function (scope, iElement, iAttrs) {
        scope.ngShow = function () {
          return iAttrs.gtShow ? scope.gtShow() : true;
        };
        scope.isActive = function () {
          return scope.$parent.activeNavbarItem === scope.gtItemName;
        };
        scope.ngClick = function (event) {
          // need to collapse the navbar in mobile view
          var $navbarCollapse = $('.navbar-collapse');
          $navbarCollapse.removeClass('in');
          $navbarCollapse.addClass('collapse');
          if ($location.path() === '/' + scope.gtUrl && !event.ctrlKey) {
            // inherit false prevents current state from being passed
            // e.g. without inherit false transaction-type=Background will be passed
            // which will defeat the point of reloading the page fresh (with no explicit transaction-type)
            $state.go($state.$current, null, {reload: true, inherit: false});
            // suppress normal link
            event.preventDefault();
            return false;
          }
          // blur navbar item so it doesn't have highlight around it (at least in firefox)
          document.activeElement.blur();
        };
      }
    };
  }
]);

glowroot.directive('gtSidebarItem', [
  '$location',
  '$state',
  function ($location, $state) {
    return {
      scope: {
        gtDisplay: '@',
        gtDisplayRight: '@',
        gtUrl: '@',
        gtShow: '&',
        gtActive: '&',
        gtClick: '&'
      },
      // replace is needed in order to not mess up bootstrap css hierarchical selectors
      replace: true,
      templateUrl: 'template/gt-sidebar-item.html',
      link: function (scope, iElement, iAttrs) {
        scope.ngShow = function () {
          return iAttrs.gtShow ? scope.gtShow() : true;
        };
        scope.isActive = function () {
          return iAttrs.gtActive ? scope.gtActive() : $location.path() === '/' + scope.gtUrl;
        };
        scope.ngClick = function (event) {
          if (iAttrs.gtClick && !event.ctrlKey) {
            // this is used by transaction sidebar
            scope.gtClick();
            // suppress normal link
            event.preventDefault();
            return false;
          } else if ($location.path() === '/' + scope.gtUrl && !event.ctrlKey) {
            $state.go($state.$current, null, {reload: true});
            // suppress normal link
            event.preventDefault();
            return false;
          }
          // blur navbar item so it doesn't have highlight around it
          document.activeElement.blur();
        };
      }
    };
  }
]);

glowroot.directive('gtSetFocus', function () {
  return function (scope, iElement, iAttrs) {
    scope.$watch(iAttrs.gtSetFocus,
        function (newValue) {
          if (newValue) {
            iElement.focus();
          }
        }, true);
  };
});

glowroot.directive('gtDisplayWhitespace', function () {
  return {
    scope: {
      gtBind: '&'
    },
    link: function (scope, iElement, iAttrs) {
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
    iElement.addClass('hide');
    scope.$watch(iAttrs.gtShow,
        function (newValue) {
          if (newValue) {
            if (spinner === undefined) {
              var left;
              if (iAttrs.gtSpinnerInline) {
                left = 10;
              }
              // z-index should be less than navbar (which is 1030)
              spinner = new Spinner({lines: 9, radius: 8, width: 5, left: left, zIndex: 1020});
            }
            if (iAttrs.gtNoDelay) {
              iElement.removeClass('hide');
              spinner.spin(iElement[0]);
            } else {
              // small delay so that if there is an immediate response the spinner doesn't blink
              //
              // must clear previous timer, e.g. in case multiple keystrokes are entered on typeahead
              // inside of 100 milliseconds
              clearTimeout(timer);
              timer = setTimeout(function () {
                iElement.removeClass('hide');
                spinner.spin(iElement[0]);
              }, 100);
            }
          } else if (spinner !== undefined) {
            clearTimeout(timer);
            iElement.addClass('hide');
            spinner.stop();
          }
        });
  };
});

glowroot.directive('gtFormWithPrimaryButton', function () {
  return function (scope, iElement, iAttrs) {
    iElement.on('keypress', 'input', function (e) {
      if (e.which === 13) {
        // only click on button if it is enabled
        iElement.find('.btn-primary:enabled').first().click();
      }
    });
  };
});

glowroot.directive('gtFormAutofocusOnFirstInput', function () {
  return function (scope, iElement, iAttrs) {
    var unregisterWatch = scope.$watch(function () {
      return iElement.find('input').length && iElement.find('input').first().is(':visible');
    }, function (newValue) {
      if (newValue) {
        // setTimeout is sometimes needed for IE9, e.g. on Config > Profiling
        setTimeout(function () {
          iElement.find('input').first().focus();
        });
        unregisterWatch();
      }
    });
  };
});

glowroot.directive('gtSmartClick', function () {
  return {
    scope: {
      gtSmartClick: '&'
    },
    link: function (scope, iElement, iAttrs) {
      iElement.mousedown(function (e) {
        scope.mousedownPageX = e.pageX;
        scope.mousedownPageY = e.pageY;
      });
      iElement.click(function (event, keyboard) {
        if (!keyboard && (Math.abs(event.pageX - scope.mousedownPageX) > 5 ||
            Math.abs(event.pageY - scope.mousedownPageY) > 5)) {
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
