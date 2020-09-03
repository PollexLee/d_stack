/*
 * Created with Android Studio.
 * User: whqfor
 * Date: 2020-02-03
 * Time: 14:20
 * email: wanghuaqiang@tal.com
 * tartget: plugin人口
 */

import 'package:d_stack/channel/dchannel.dart';
import 'package:d_stack/navigator/dnavigator_gesture_observer.dart';
import 'package:d_stack/navigator/dnavigator_manager.dart';
import 'package:d_stack/observer/life_cycle_observer.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

enum PageType { native, flutter }

typedef DStackWidgetBuilder = WidgetBuilder Function(Map params);

class DStack {
  static DChannel _stackChannel;
  static final DStack _instance = DStack();

  static DStack get instance {
    final MethodChannel _methodChannel = MethodChannel("d_stack");
    _stackChannel = DChannel(_methodChannel);
    return _instance;
  }

  DChannel get channel => _stackChannel;

  // 全局无context
  GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

  // 获取navigator
  NavigatorState get navigator => navigatorKey.currentState;

  // 用来监听手势
  final DStackNavigatorObserver dStackNavigatorObserver =
      DStackNavigatorObserver();

  // 用来监听 应用生命周期
  DLifeCycleObserver dLifeCycleObserver;

  final Map<String, DStackWidgetBuilder> _pageBuilders =
      <String, DStackWidgetBuilder>{};

  void register(
      {Map<String, DStackWidgetBuilder> builders,
      DLifeCycleObserver observer}) {
    if (builders?.isNotEmpty == true) {
      _pageBuilders.addAll(builders);
    }
    dLifeCycleObserver = observer;
  }

  DStackWidgetBuilder pageBuilder(String pageName) {
    DStackWidgetBuilder builder = _pageBuilders[pageName];
    if (builder != null) {
      return builder;
    } else {
      throw Exception('not in the PageRoute');
    }
  }

  // 获取节点列表
  Future<List<DStackNode>> nodeList() {
    return channel.getNodeList();
  }

  /// routeName 路由名，pageType native或者flutter, params 参数
  static Future push(String routeName, PageType pageType,
      {Map params, String storyboard, String identifier}) {
    return DNavigatorManager.push(
        routeName, pageType, params, storyboard, identifier);
  }

  /// 提供外界直接传builder的能力
  static Future pushBuild(String routeName, WidgetBuilder builder,
      {Map params}) {
    return DNavigatorManager.pushBuild(routeName, builder, params);
  }

  /// 只支持flutter使用，替换flutter页面
  static Future replace(String routeName, PageType pageType,
      {Map params, String storyboard, String identifier}) {
    return DNavigatorManager.replace(
        routeName, pageType, params, storyboard, identifier);
  }

  /// result 返回值，可为空
  /// pop可以不传路由信息
  static void pop({Map result}) {
    DNavigatorManager.pop(result);
  }

  static void popWithGesture() {
    DNavigatorManager.popWithGesture();
  }

  static void popTo(String routeName, PageType pageType, {Map result}) {
    DNavigatorManager.popTo(routeName, pageType, result);
  }

  static void popToNativeRoot() {
    DNavigatorManager.popToNativeRoot();
  }

  static void popSkip(String skipName, {Map result}) {
    DNavigatorManager.popSkip(skipName, result);
  }

  static void present(String routeName, PageType pageType,
      {Map params, String storyboard, String identifier}) {
    DNavigatorManager.present(
        routeName, pageType, params, storyboard, identifier);
  }

  static void dismiss([Map result]) {
    DNavigatorManager.dismiss(result);
  }
}

class DStackNode {
  final String route;
  final String pageType;
  DStackNode({this.route, this.pageType});
}