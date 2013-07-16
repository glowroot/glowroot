/*
 * Copyright 2012-2013 the original author or authors.
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

/* global angular, $, Spinner */
/* jshint strict: false */

var informant = angular.module('informant', ['ui.bootstrap']);

informant.config(function ($locationProvider, $routeProvider) {
  $locationProvider.html5Mode(true);
  $routeProvider.when('/', {
    templateUrl: 'views/home.html',
    controller: 'HomeCtrl'
  });
  $routeProvider.when('/search.html', {
    templateUrl: 'views/search.html',
    controller: 'SearchCtrl'
  });
  $routeProvider.when('/config.html', {
    templateUrl: 'views/config.html',
    controller: 'ConfigCtrl'
  });
  $routeProvider.when('/pointcuts.html', {
    templateUrl: 'views/pointcuts.html',
    controller: 'PointcutsCtrl'
  });
  $routeProvider.when('/threaddump.html', {
    templateUrl: 'views/threaddump.html',
    controller: 'ThreaddumpCtrl'
  });
});

informant.controller('MainCtrl', function ($scope) {
  $scope.title = '';
});

informant.factory('ixButtonGroupControllerFactory', function ($q) {
  return {
    create: function (element) {
      var $element = $(element);
      var $buttonMessage = $element.find('.button-message');
      var $buttonSpinner = $element.find('.button-spinner');
      var alreadyExecuting = false;
      return {
        onClick: function (fn, validate) {
          // handle crazy user clicking on the button
          if (alreadyExecuting) {
            return;
          }
          // validate form
          if (validate) {
            var form = element.parent().controller('form');
            if (!form.$valid) {
              $buttonMessage.text('Please fix form errors');
              $buttonMessage.removeClass('button-message-success');
              $buttonMessage.addClass('button-message-error');
              $buttonMessage.removeClass('hide');
              return;
            }
          }
          // in case button is clicked again before message fades out
          $buttonMessage.addClass('hide');
          Informant.showSpinner($buttonSpinner);

          var deferred = $q.defer();
          deferred.promise.then(function (success) {
            Informant.hideSpinner($buttonSpinner);
            $buttonMessage.text(success);
            $buttonMessage.removeClass('button-message-error');
            $buttonMessage.addClass('button-message-success');
            Informant.showAndFadeSuccessMessage($buttonMessage);
            alreadyExecuting = false;
          }, function (error) {
            Informant.hideSpinner($buttonSpinner);
            $buttonMessage.text(error);
            $buttonMessage.removeClass('button-message-success');
            $buttonMessage.addClass('button-message-error');
            $buttonMessage.removeClass('hide');
            alreadyExecuting = false;
          });

          alreadyExecuting = true;
          fn({deferred: deferred});
        }
      };
    }
  };
});

informant.directive('ixButtonGroup', function (ixButtonGroupControllerFactory) {
  return {
    scope: {},
    transclude: true,
    template: '<span ng-transclude></span>' +
        '<span class="button-message hide"></span>' +
        '<span class="button-spinner inline-block hide"></span>',
    controller: function ($element) {
      return ixButtonGroupControllerFactory.create($element);
    }
  };
});

informant.directive('ixButton', function (ixButtonGroupControllerFactory) {
  return {
    scope: {
      ixLabel: '@',
      ixClick: '&',
      ixShow: '&',
      ixDontValidateForm: '@'
    },
    template: function (element, attrs) {
      var ixButtonGroup = element.parent().controller('ixButtonGroup');
      var ngShow = attrs.ixShow ? ' ng-show="ixShow()"' : '';
      if (ixButtonGroup) {
        return '<button class="btn" ng-click="onClick()"' + ngShow + '>{{ixLabel}}</button>';
      } else {
        return '<button class="btn" ng-click="onClick()"' + ngShow + '>{{ixLabel}}</button>' +
            '<span class="button-message hide"></span>' +
            '<span class="button-spinner inline-block hide"></span>';
      }
    },
    require: '^?ixButtonGroup',
    link: function (scope, element, attrs, ixButtonGroup) {
      var form = element.parent().controller('form');
      if (!ixButtonGroup) {
        scope.noGroup = true;
        ixButtonGroup = ixButtonGroupControllerFactory.create(element);
      }
      scope.onClick = function () {
        ixButtonGroup.onClick(scope.ixClick, form && !scope.ixDontValidateForm);
      };
    }
  };
});

informant.directive('ixControlGroup', function ($compile) {
  return {
    scope: {
      ixLabel: '@',
      ixModel: '=',
      ixType: '@',
      ixPattern: '@',
      ixWidth: '@',
      ixAddOn: '@',
      ixRequired: '@'
    },
    transclude: true,
    require: '^form',
    compile: function (element, attrs, transclude) {
      return function (scope, element, attrs, form) {
        var type = scope.ixType || 'text';
        var style = scope.ixWidth ? ' style="width: ' + scope.ixWidth + '"' : '';
        scope.form = form;
        var template = '<div class="control-group" ng-class="{error: form.id' + scope.$id + '.$invalid}">' +
            '  <label class="control-label" for="id' + scope.$id + '">' +
            '    {{ixLabel}}' +
            '  </label>' +
            '  <div class="controls" ng-transclude>';
        if (scope.ixAddOn) {
          template += '<div class="input-append">';
        }
        template += '<input type="' + type + '"' +
            ' ng-model="ixModel"' +
            ' name="id' + scope.$id + '"' +
            ' id="id{{$id}}"' +
            ' ng-pattern="{{ixPattern}}"' +
            ' ng-required="{{ixRequired}}"' +
            style + '>';
        if (scope.ixAddOn) {
          template += '<span class="add-on">{{ixAddOn}}</span>';
        }
        template += '</div></div></div>';

        element.html(template);
        $compile(element.contents(), transclude)(scope);
      };
    }
  };
});

informant.directive('ixDatepicker', function () {
  return {
    scope: {
      ixModel: '=',
      ixClass: '@',
      ixId: '@'
    },
    template: '<input type="text" class="input-small {{ixClass}}" id="{{ixId}}">',
    link: function (scope, element, attrs) {
      // TODO use bootstrap-datepicker momentjs backend when it's available and then use momentjs's
      // localized format 'moment.longDateFormat.L' both here and when parsing date
      // see https://github.com/eternicode/bootstrap-datepicker/issues/24
      var $input = $(element).find('input');
      $input.datepicker({format: 'mm/dd/yyyy', autoclose: true, todayHighlight: true});
      $input.datepicker('setDate', scope.ixModel);
      $input.on('changeDate', function (event) {
        scope.$apply(function () {
          scope.ixModel = event.date;
        });
      });
    }
  };
});

informant.directive('ixSetFocus', function () {
  return function (scope, element, attrs) {
    scope.$watch(attrs.ixFocus,
        function (newValue) {
          if (newValue) {
            element.focus();
          }
        }, true);
  };
});

// until ngBlur is included in angularjs
informant.directive('ixBlur', function ($parse) {
  return function (scope, element, attr) {
    var fn = $parse(attr.ixBlur);
    element.bind('blur', function (event) {
      scope.$apply(function () {
        fn(scope, {$event: event});
      });
    });
  };
});

$(document).ready(function () {
  // bootstrap-dropdown already closes the menu on esc key, this is just adding handling for tab
  // key to close menu also, e.g. user is looking at menu and then tabs away
  var onTabCloseMenu = function (e) {
    // tab key
    if (e.keyCode === 9) {
      $('.header-see-also').removeClass('open');
    }
  };
  $('.header-see-also [data-toggle=dropdown], .header-see-also [role=menu]')
      .on('keydown touchstart', onTabCloseMenu);
});

var Informant = {};

Informant.showAndFadeSuccessMessage = function (selector) {
  showAndFadeMessage(selector, 0);
};

// TODO unused
Informant.showAndFadeErrorMessage = function (selector) {
  showAndFadeMessage(selector, 1000);
};

var showAndFadeMessage = function (selector, delay) {
  $(selector).each(function () {
    // handle crazy user clicking on the button
    var $this = $(this);
    if ($this.data('timeout')) {
      clearTimeout($this.data('timeout'));
    }
    $this.stop().animate({opacity: '100'});
    $this.removeClass('hide');
    var outerThis = this;
    $this.data('timeout', setTimeout(function () {
      fadeOut(outerThis, 1000);
    }, delay));
  });
};

var fadeOut = function (selector, duration) {
  // fade out and then override jquery behavior and use hide class instead of display: none
  var $selector = $(selector);
  $selector.fadeOut(duration, function () {
    $selector.addClass('hide');
    $selector.css('display', '');
  });
};

Informant.showSpinner = function (selector, opts) {
  opts = opts || { lines: 10, width: 4, radius: 8 };
  $(selector).each(function () {
    var data = $(this).data();
    data.spinner = new Spinner(opts);
    var outerThis = this;

    function displaySpinner() {
      // data.spinner may have been cleared already by hideSpinner() before setTimeout triggered
      if (data.spinner) {
        $(outerThis).removeClass('hide');
        data.spinner.spin(outerThis);
      }
    }

    // small delay so that if there is an immediate response the spinner doesn't blink
    setTimeout(displaySpinner, 100);
  });
};

Informant.hideSpinner = function (selector) {
  $(selector).each(function () {
    var $this = $(this);
    var data = $this.data();
    if (data.spinner) {
      data.spinner.stop();
      delete data.spinner;
    }
    $this.addClass('hide');
  });
};

Informant.configureAjaxError = function () {
  var modalDiv =
      '<div class="modal hide fade" id="ajaxErrorModal" tabindex="-1"' +
          '    style="width: 800px; margin: -300px 0 0 -400px; max-height: 600px;">' +
          '  <div class="modal-header">' +
          '    <button class="close" data-dismiss="modal">&times;</button>' +
          '    <h3>Ajax Error</h3>' +
          '  </div>' +
          '  <div class="modal-body" id="ajaxError"></div>' +
          '  <div class="modal-footer">' +
          '    <button class="btn" data-dismiss="modal">Close</button>' +
          '  </div>' +
          '</div>';
  $(document.body).append(modalDiv);
  $(document).ajaxError(function (e, jqxhr, settings, exception) {
    if (jqxhr.abort) {
      // intentional abort (currently happens in firefox if open trace detail modal, detail takes
      // long to load, hit escape key to close trace detail modal before detail request completes)
      return;
    }
    var $ajaxError = $('#ajaxError');
    if (jqxhr.status === 0) {
      $ajaxError.html('Can\'t connect to server');
    } else if (jqxhr.status === 200) {
      $ajaxError.html('Error parsing json: ' + exception);
      $ajaxError.append('<br><br>');
      $ajaxError.append(jqxhr.responseText);
    } else {
      $ajaxError.html('Error from server: ' + jqxhr.statusText);
    }
    $('#ajaxErrorModal').modal('show');
  });
};

$(document).on('keypress', '.refresh-data-on-enter-key', function (event) {
  if (event.which === 13) {
    // trigger button so it will active spinner and success message
    $('.form-submit > button').click();
    // without preventDefault, enter triggers 'more filters' button
    event.preventDefault();
  }
});
