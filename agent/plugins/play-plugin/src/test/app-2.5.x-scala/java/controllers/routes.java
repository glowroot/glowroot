// @GENERATOR:play-routes-compiler

package controllers;

import router.RoutesPrefix;

public class routes {

    public static final controllers.ReverseAssets Assets =
            new controllers.ReverseAssets(RoutesPrefix.byNamePrefix());
    public static final controllers.ReverseBadController BadController =
            new controllers.ReverseBadController(RoutesPrefix.byNamePrefix());
    public static final controllers.ReverseHomeController HomeController =
            new controllers.ReverseHomeController(RoutesPrefix.byNamePrefix());
    public static final controllers.ReverseAsyncController AsyncController =
            new controllers.ReverseAsyncController(RoutesPrefix.byNamePrefix());
    public static final controllers.ReverseStreamController StreamController =
            new controllers.ReverseStreamController(RoutesPrefix.byNamePrefix());

    public static class javascript {

        public static final controllers.javascript.ReverseAssets Assets =
                new controllers.javascript.ReverseAssets(RoutesPrefix.byNamePrefix());
        public static final controllers.javascript.ReverseBadController BadController =
                new controllers.javascript.ReverseBadController(RoutesPrefix.byNamePrefix());
        public static final controllers.javascript.ReverseHomeController HomeController =
                new controllers.javascript.ReverseHomeController(RoutesPrefix.byNamePrefix());
        public static final controllers.javascript.ReverseAsyncController AsyncController =
                new controllers.javascript.ReverseAsyncController(RoutesPrefix.byNamePrefix());
        public static final controllers.javascript.ReverseStreamController StreamController =
                new controllers.javascript.ReverseStreamController(RoutesPrefix.byNamePrefix());
    }
}
