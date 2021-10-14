package io.invertase.firebase.messaging;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.facebook.react.HeadlessJsTaskService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.HashMap;

import io.invertase.firebase.app.ReactNativeFirebaseApp;
import io.invertase.firebase.common.ReactNativeFirebaseEventEmitter;
import io.invertase.firebase.common.SharedUtils;

public class ReactNativeFirebaseMessagingReceiver extends BroadcastReceiver {
  private static final String TAG = "RNFirebaseMsgReceiver";
  static HashMap<String, RemoteMessage> notifications = new HashMap<>();
  static int NOTIFICATION_MAX_SIZE = (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ? 25 : 50);
  NotificationManager notificationManager;
  @RequiresApi(api = Build.VERSION_CODES.M)
  @Override
  public void onReceive(Context context, Intent intent) {
    notificationManager = context.getSystemService(NotificationManager.class);
    Log.d(TAG, "broadcast received for message , current android version is "+Build.VERSION.SDK_INT  + " and notifications max size is "+  NOTIFICATION_MAX_SIZE);
    checkNotificationLimit();
    if (ReactNativeFirebaseApp.getApplicationContext() == null) {
      ReactNativeFirebaseApp.setApplicationContext(context.getApplicationContext());
    }
    RemoteMessage remoteMessage = new RemoteMessage(intent.getExtras());
    ReactNativeFirebaseEventEmitter emitter = ReactNativeFirebaseEventEmitter.getSharedInstance();

    // Add a RemoteMessage if the message contains a notification payload
    if (remoteMessage.getNotification() != null) {
      notifications.put(remoteMessage.getMessageId(), remoteMessage);
      ReactNativeFirebaseMessagingStoreHelper.getInstance()
        .getMessagingStore()
        .storeFirebaseMessage(remoteMessage);
    }

    //  |-> ---------------------
    //      App in Foreground
    //   ------------------------
    if (SharedUtils.isAppInForeground(context)) {
      emitter.sendEvent(
        ReactNativeFirebaseMessagingSerializer.remoteMessageToEvent(remoteMessage, false));
      return;
    }

    //  |-> ---------------------
    //    App in Background/Quit
    //   ------------------------

    try {
      Intent backgroundIntent =
        new Intent(context, ReactNativeFirebaseMessagingHeadlessService.class);
      backgroundIntent.putExtra("message", remoteMessage);
      ComponentName name = context.startService(backgroundIntent);
      if (name != null) {
        HeadlessJsTaskService.acquireWakeLockNow(context);
      }
    } catch (IllegalStateException ex) {
      // By default, data only messages are "default" priority and cannot trigger Headless tasks
      Log.e(TAG, "Background messages only work if the message priority is set to 'high'", ex);
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.M)
  private void checkNotificationLimit(){
    StatusBarNotification[] statusBarNotifications = notificationManager.getActiveNotifications();
    for(int i=0;i<statusBarNotifications.length;i++){
      StatusBarNotification statusBarNotification = statusBarNotifications[i];
      Log.d(TAG , " --- "+ i+ "ID = "+statusBarNotification.getId()+
        "tag "+statusBarNotification.getTag()+
        "StatusBarNotification pkg = "+ statusBarNotification.getPackageName()+ "notification =  " + statusBarNotification.getNotification()
        +" channelId = "+statusBarNotification.getNotification());
    }
    if (statusBarNotifications.length >= NOTIFICATION_MAX_SIZE) {
      for (StatusBarNotification sbn : statusBarNotifications) {
        //id 为2147483647 整数最大值 删除不了这个是android的一个源码bug。删除下一跳
        if (sbn.getId() == 2147483647) {
          notificationManager.cancel(sbn.getTag(), sbn.getId());
          continue;
        }
        Log.d(TAG, "checkNotificationLimit : cancelNotification " + " tag : " + sbn.getTag() + " id : " + sbn.getId());
        notificationManager.cancel(sbn.getTag(), sbn.getId());
        return;
      }
    }
  }
}
