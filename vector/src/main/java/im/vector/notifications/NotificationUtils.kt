/*
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.notifications

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.support.annotation.ColorInt
import android.support.annotation.StringRes
import android.support.v4.app.*
import android.support.v4.content.ContextCompat
import android.text.TextUtils
import im.vector.BuildConfig
import im.vector.Matrix
import im.vector.R
import im.vector.VectorApp
import im.vector.activity.JoinRoomActivity
import im.vector.activity.LockScreenActivity
import im.vector.activity.VectorHomeActivity
import im.vector.activity.VectorRoomActivity
import im.vector.receiver.NotificationBroadcastReceiver
import im.vector.ui.themes.ThemeUtils
import im.vector.util.PreferencesManager
import im.vector.util.startNotificationChannelSettingsIntent
import org.matrix.androidsdk.util.Log
import java.util.*


fun supportNotificationChannels() = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

/**
 * Util class for creating notifications.
 */
object NotificationUtils {
    private val LOG_TAG = NotificationUtils::class.java.simpleName

    /* ==========================================================================================
     * IDs for notifications
     * ========================================================================================== */

    /**
     * Identifier of the notification used to display messages.
     * Those messages are merged into a single notification.
     */
    private const val NOTIFICATION_ID_MESSAGES = 60

    /**
     * Identifier of the foreground notification used to keep the application alive
     * when it runs in background.
     * This notification, which is not removable by the end user, displays what
     * the application is doing while in background.
     */
    const val NOTIFICATION_ID_FOREGROUND_SERVICE = 61

    /* ==========================================================================================
     * IDs for actions
     * ========================================================================================== */

    private const val JOIN_ACTION = "NotificationUtils.JOIN_ACTION"
    private const val REJECT_ACTION = "NotificationUtils.REJECT_ACTION"
    private const val QUICK_LAUNCH_ACTION = "NotificationUtils.QUICK_LAUNCH_ACTION"
    const val SMART_REPLY_ACTION = "${BuildConfig.APPLICATION_ID}.NotificationUtils.SMART_REPLY_ACTION"
    const val DISMISS_SUMMARY_ACTION = "${BuildConfig.APPLICATION_ID}.NotificationUtils.DISMISS_SUMMARY_ACTION"
    const val DISMISS_ROOM_NOTIF_ACTION = "${BuildConfig.APPLICATION_ID}.NotificationUtils.DISMISS_ROOM_NOTIF_ACTION"
    const val TAP_TO_VIEW_ACTION = "${BuildConfig.APPLICATION_ID}.NotificationUtils.TAP_TO_VIEW_ACTION"

    /* ==========================================================================================
     * IDs for channels
     * ========================================================================================== */

    // on devices >= android O, we need to define a channel for each notifications
    private const val LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID = "LISTEN_FOR_EVENTS_NOTIFICATION_CHANNEL_ID"

    private const val NOISY_NOTIFICATION_CHANNEL_ID = "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID"

    private const val SILENT_NOTIFICATION_CHANNEL_ID = "DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID_V2"
    private const val CALL_NOTIFICATION_CHANNEL_ID = "CALL_NOTIFICATION_CHANNEL_ID_V2"

    /* ==========================================================================================
     * Channel names
     * ========================================================================================== */

    /**
     * Create notification channels.
     *
     * @param context the context
     */
    @TargetApi(Build.VERSION_CODES.O)
    fun createNotificationChannels(context: Context) {
        if (!supportNotificationChannels()) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        //Migration - the noisy channel was deleted and recreated when sound preference was changed (id was DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_BASE
        // + currentTimeMillis).
        //Now the sound can only be change directly in system settings, so for app upgrading we are deleting this former channel
        //Starting from this version the channel will not be dynamic
        for (channel in notificationManager.notificationChannels) {
            val channelId = channel.id
            val legacyBaseName = "DEFAULT_NOISY_NOTIFICATION_CHANNEL_ID_BASE"
            if (channelId.startsWith(legacyBaseName)) {
                notificationManager.deleteNotificationChannel(channelId)
            }
        }
        //Migration - Remove deprecated channels
        for (channelId in listOf("DEFAULT_SILENT_NOTIFICATION_CHANNEL_ID", "CALL_NOTIFICATION_CHANNEL_ID")) {
            notificationManager.getNotificationChannel(channelId)?.let {
                notificationManager.deleteNotificationChannel(channelId)
            }
        }

        /**
         * Default notification importance: shows everywhere, makes noise, but does not visually
         * intrude.
         */

        val noisyChannel = NotificationChannel(NOISY_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_noisy_notifications),
                NotificationManager.IMPORTANCE_DEFAULT)
        noisyChannel.description = context.getString(R.string.notification_noisy_notifications)
        noisyChannel.enableVibration(true)
        noisyChannel.enableLights(true)
        notificationManager.createNotificationChannel(noisyChannel)

        /**
         * Low notification importance: shows everywhere, but is not intrusive.
         */
        val silentChannel = NotificationChannel(SILENT_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_silent_notifications),
                NotificationManager.IMPORTANCE_LOW)
        silentChannel.description = context.getString(R.string.notification_silent_notifications)
        silentChannel.setSound(null, null)
        silentChannel.enableLights(true)
        notificationManager.createNotificationChannel(silentChannel)

        val listeningForEventChannel = NotificationChannel(LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_listening_for_events),
                NotificationManager.IMPORTANCE_MIN)
        listeningForEventChannel.description = context.getString(R.string.notification_listening_for_events)
        listeningForEventChannel.setSound(null, null)
        listeningForEventChannel.setShowBadge(false)
        notificationManager.createNotificationChannel(listeningForEventChannel)

        val callChannel = NotificationChannel(CALL_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.call),
                NotificationManager.IMPORTANCE_HIGH)
        callChannel.description = context.getString(R.string.call)
        callChannel.setSound(null, null)
        callChannel.enableLights(true)
        callChannel.lightColor = Color.GREEN
        notificationManager.createNotificationChannel(callChannel)
    }

    /**
     * Build a polling thread listener notification
     *
     * @param context       Android context
     * @param subTitleResId subtitle string resource Id of the notification
     * @return the polling thread listener notification
     */
    @SuppressLint("NewApi")
    fun buildForegroundServiceNotification(context: Context, @StringRes subTitleResId: Int): Notification {
        // build the pending intent go to the home screen if this is clicked.
        val i = Intent(context, VectorHomeActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(context, 0, i, 0)

        val builder = NotificationCompat.Builder(context, LISTENING_FOR_EVENTS_NOTIFICATION_CHANNEL_ID)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(context.getString(R.string.riot_app_name))
                .setContentText(context.getString(subTitleResId))
                .setSmallIcon(R.drawable.logo_transparent)
                .setContentIntent(pi)

        // hide the notification from the status bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.priority = NotificationCompat.PRIORITY_MIN
        }

        val notification = builder.build()

        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // some devices crash if this field is not set
            // even if it is deprecated

            // setLatestEventInfo() is deprecated on Android M, so we try to use
            // reflection at runtime, to avoid compiler error: "Cannot resolve method.."
            try {
                val deprecatedMethod = notification.javaClass
                        .getMethod("setLatestEventInfo", Context::class.java, CharSequence::class.java, CharSequence::class.java, PendingIntent::class.java)
                deprecatedMethod.invoke(notification, context, context.getString(R.string.riot_app_name), context.getString(subTitleResId), pi)
            } catch (ex: Exception) {
                Log.e(LOG_TAG, "## buildNotification(): Exception - setLatestEventInfo() Msg=" + ex.message, ex)
            }

        }
        return notification
    }

    /**
     * Build an incoming call notification.
     * This notification starts the VectorHomeActivity which is in charge of centralizing the incoming call flow.
     *
     * @param context  the context.
     * @param isVideo  true if this is a video call, false for voice call
     * @param roomName the room name in which the call is pending.
     * @param matrixId the matrix id
     * @param callId   the call id.
     * @return the call notification.
     */
    @SuppressLint("NewApi")
    fun buildIncomingCallNotification(context: Context, isVideo: Boolean, roomName: String, matrixId: String, callId: String): Notification {

        val builder = NotificationCompat.Builder(context, CALL_NOTIFICATION_CHANNEL_ID)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ensureTitleNotEmpty(context, roomName))
                .apply {
                    if (isVideo) {
                        setContentText(context.getString(R.string.incoming_video_call))
                    } else {
                        setContentText(context.getString(R.string.incoming_voice_call))
                    }
                }
                .setSmallIcon(R.drawable.incoming_call_notification_transparent)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setLights(Color.GREEN, 500, 500)

        //Compat: Display the incoming call notification on the lock screen
        builder.priority = NotificationCompat.PRIORITY_MAX

        // clear the activity stack to home activity
        val intent = Intent(context, VectorHomeActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VectorHomeActivity.EXTRA_CALL_SESSION_ID, matrixId)
                .putExtra(VectorHomeActivity.EXTRA_CALL_ID, callId)

        // Recreate the back stack
        val stackBuilder = TaskStackBuilder.create(context)
                .addParentStack(VectorHomeActivity::class.java)
                .addNextIntent(intent)


        // android 4.3 issue
        // use a generator for the private requestCode.
        // When using 0, the intent is not created/launched when the user taps on the notification.
        //
        val pendingIntent = stackBuilder.getPendingIntent(Random().nextInt(1000), PendingIntent.FLAG_UPDATE_CURRENT)

        builder.setContentIntent(pendingIntent)

        return builder.build()
    }

    /**
     * Build a pending call notification
     *
     * @param context  the context.
     * @param isVideo  true if this is a video call, false for voice call
     * @param roomName the room name in which the call is pending.
     * @param roomId   the room Id
     * @param matrixId the matrix id
     * @param callId   the call id.
     * @return the call notification.
     */
    @SuppressLint("NewApi")
    fun buildPendingCallNotification(context: Context, isVideo: Boolean, roomName: String, roomId: String, matrixId: String, callId: String): Notification {

        val builder = NotificationCompat.Builder(context, CALL_NOTIFICATION_CHANNEL_ID)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(ensureTitleNotEmpty(context, roomName))
                .apply {
                    if (isVideo) {
                        setContentText(context.getString(R.string.call_in_progress))
                    } else {
                        setContentText(context.getString(R.string.video_call_in_progress))
                    }
                }
                .setSmallIcon(R.drawable.incoming_call_notification_transparent)

        // Display the pending call notification on the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.priority = NotificationCompat.PRIORITY_MAX
        }

        // Build the pending intent for when the notification is clicked
        val roomIntent = Intent(context, VectorRoomActivity::class.java)
                .putExtra(VectorRoomActivity.EXTRA_ROOM_ID, roomId)
                .putExtra(VectorRoomActivity.EXTRA_MATRIX_ID, matrixId)
                .putExtra(VectorRoomActivity.EXTRA_START_CALL_ID, callId)

        // Recreate the back stack
        val stackBuilder = TaskStackBuilder.create(context)
                .addParentStack(VectorRoomActivity::class.java)
                .addNextIntent(roomIntent)

        // android 4.3 issue
        // use a generator for the private requestCode.
        // When using 0, the intent is not created/launched when the user taps on the notification.
        //
        val pendingIntent = stackBuilder.getPendingIntent(Random().nextInt(1000), PendingIntent.FLAG_UPDATE_CURRENT)

        builder.setContentIntent(pendingIntent)

        return builder.build()
    }

    /**
     * Add a text style to a notification when there are several notified rooms.
     *
     * @param context            the context
     * @param builder            the notification builder
     * @param roomsNotifications the rooms notifications
     */
//    @Deprecated("will be removed")
//    private fun addTextStyleWithSeveralRooms(context: Context,
//                                             builder: NotificationCompat.Builder,
//                                             roomsNotifications: RoomsNotifications) {
//        val inboxStyle = NotificationCompat.InboxStyle()
//
//
//        for (roomNotifications in roomsNotifications.mRoomNotifications) {
//            val notifiedLine = SpannableString(roomNotifications.mMessagesSummary)
//            notifiedLine.setSpan(StyleSpan(android.graphics.Typeface.BOLD),
//                    0, roomNotifications.mMessageHeader.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
//            inboxStyle.addLine(notifiedLine)
//        }
//
//        inboxStyle.setBigContentTitle(context.getString(R.string.riot_app_name))
//        inboxStyle.setSummaryText(roomsNotifications.mSummaryText)
//        builder.setStyle(inboxStyle)
//
//        val stackBuilderTap = TaskStackBuilder.create(context)
//        val roomIntentTap: Intent?
//
//        // add the home page the activity stack
//        stackBuilderTap.addNextIntentWithParentStack(Intent(context, VectorHomeActivity::class.java))
//
//        if (roomsNotifications.mIsInvitationEvent) {
//            // for invitation the room preview must be displayed
//            roomIntentTap = CommonActivityUtils.buildIntentPreviewRoom(roomsNotifications.mSessionId,
//                    roomsNotifications.mRoomId, context, VectorFakeRoomPreviewActivity::class.java)
//        } else {
//            roomIntentTap = Intent(context, VectorRoomActivity::class.java)
//            roomIntentTap.putExtra(VectorRoomActivity.EXTRA_ROOM_ID, roomsNotifications.mRoomId)
//        }
//
//        roomIntentTap!!.action = TAP_TO_VIEW_ACTION
//        stackBuilderTap.addNextIntent(roomIntentTap)
//        builder.setContentIntent(stackBuilderTap.getPendingIntent(System.currentTimeMillis().toInt(), 0))
//
//        // offer to open the rooms list
//        run {
//            val openIntentTap = Intent(context, VectorHomeActivity::class.java)
//
//            // Recreate the back stack
//            val viewAllTask = TaskStackBuilder.create(context)
//                    .addNextIntent(openIntentTap)
//
//            builder.addAction(
//                    R.drawable.ic_home_black_24dp,
//                    context.getString(R.string.bottom_action_home),
//                    viewAllTask.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
//        }
//    }

    /**
     * Add a text style for a bunch of notified events.
     *
     *
     * The notification contains the notified messages from any rooms.
     * It does not contain anymore the latest notified message.
     *
     *
     * When there is only one room, it displays the MAX_NUMBER_NOTIFICATION_LINES latest messages.
     * The busy ones are displayed in RED.
     * The QUICK REPLY and other buttons are displayed.
     *
     *
     * When there are several rooms, it displays the busy notified rooms first (sorted by latest message timestamp).
     * Each line is
     * - "Room Name : XX unread messages" if there are many unread messages
     * - 'Room Name : Sender   - Message body" if there is only one unread message.
     *
     * @param context            the context
     * @param builder            the notification builder
     * @param roomsNotifications the rooms notifications
     */
//    @Deprecated("will be removed")
//    private fun addTextStyle(context: Context,
//                             builder: NotificationCompat.Builder,
//                             roomsNotifications: RoomsNotifications) {
//
//        // nothing to do
//        if (0 == roomsNotifications.mRoomNotifications.size) {
//            return
//        }
//
//        // when there are several rooms, the text style is not the same
//        if (roomsNotifications.mRoomNotifications.size > 1) {
//            addTextStyleWithSeveralRooms(context, builder, roomsNotifications)
//            return
//        }
//
//        var latestText: SpannableString? = null
//        val inboxStyle = NotificationCompat.InboxStyle()
//
//        for (sequence in roomsNotifications.mReversedMessagesList) {
//            inboxStyle.addLine(SpannableString(sequence))
//        }
//
//        inboxStyle.setBigContentTitle(roomsNotifications.mContentTitle)
//
//        // adapt the notification display to the number of notified messages
//        if (1 == roomsNotifications.mReversedMessagesList.size && null != latestText) {
//            builder.setStyle(NotificationCompat.BigTextStyle().bigText(latestText))
//        } else {
//            if (!TextUtils.isEmpty(roomsNotifications.mSummaryText)) {
//                inboxStyle.setSummaryText(roomsNotifications.mSummaryText)
//            }
//            builder.setStyle(inboxStyle)
//        }
//
//        if (roomsNotifications.mIsInvitationEvent) {
//            run {
//                // offer to type a quick reject button
//                val rejectIntent = JoinRoomActivity.getRejectRoomIntent(context, roomsNotifications.mRoomId, roomsNotifications.mSessionId)
//
//                // the action must be unique else the parameters are ignored
//                rejectIntent.action = REJECT_ACTION + System.currentTimeMillis().toInt()
//                val pIntent = PendingIntent.getActivity(context, 0, rejectIntent, 0)
//                builder.addAction(
//                        R.drawable.vector_notification_reject_invitation,
//                        context.getString(R.string.reject),
//                        pIntent)
//            }
//
//            run {
//                // offer to type a quick accept button
//                val joinIntent = JoinRoomActivity.getJoinRoomIntent(context, roomsNotifications.mRoomId, roomsNotifications.mSessionId)
//
//                // the action must be unique else the parameters are ignored
//                joinIntent.action = JOIN_ACTION + System.currentTimeMillis().toInt()
//                val pIntent = PendingIntent.getActivity(context, 0, joinIntent, 0)
//                builder.addAction(
//                        R.drawable.vector_notification_accept_invitation,
//                        context.getString(R.string.join),
//                        pIntent)
//            }
//        } else if (!LockScreenActivity.isDisplayingALockScreenActivity()) {
//            // (do not offer to quick respond if the user did not dismiss the previous one)
//
//            // offer to type a quick answer (i.e. without launching the application)
//            val quickReplyIntent = Intent(context, LockScreenActivity::class.java)
//            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_ROOM_ID, roomsNotifications.mRoomId)
//            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_SENDER_NAME, roomsNotifications.mSenderName)
//            quickReplyIntent.putExtra(LockScreenActivity.EXTRA_MESSAGE_BODY, roomsNotifications.mQuickReplyBody)
//
//            // the action must be unique else the parameters are ignored
//            quickReplyIntent.action = QUICK_LAUNCH_ACTION + System.currentTimeMillis().toInt()
//            val pIntent = PendingIntent.getActivity(context, 0, quickReplyIntent, 0)
//            builder.addAction(
//                    R.drawable.vector_notification_quick_reply,
//                    context.getString(R.string.action_quick_reply),
//                    pIntent)
//        }
//
//        // Build the pending intent for when the notification is clicked
//        val roomIntentTap: Intent
//
//        if (roomsNotifications.mIsInvitationEvent) {
//            // for invitation the room preview must be displayed
//            roomIntentTap = CommonActivityUtils.buildIntentPreviewRoom(roomsNotifications.mSessionId,
//                    roomsNotifications.mRoomId, context, VectorFakeRoomPreviewActivity::class.java)
//        } else {
//            roomIntentTap = Intent(context, VectorRoomActivity::class.java)
//            roomIntentTap.putExtra(VectorRoomActivity.EXTRA_ROOM_ID, roomsNotifications.mRoomId)
//        }
//        // the action must be unique else the parameters are ignored
//        roomIntentTap.action = TAP_TO_VIEW_ACTION + System.currentTimeMillis().toInt()
//
//        // Recreate the back stack
//        val stackBuilderTap = TaskStackBuilder.create(context)
//                .addNextIntentWithParentStack(Intent(context, VectorHomeActivity::class.java))
//                .addNextIntent(roomIntentTap)
//
//        builder.setContentIntent(stackBuilderTap.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
//
//        builder.addAction(
//                R.drawable.vector_notification_open,
//                context.getString(R.string.action_open),
//                stackBuilderTap.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
//
//        // wearable
//        if (!roomsNotifications.mIsInvitationEvent) {
//            try {
//                val wearableExtender = NotificationCompat.WearableExtender()
//                val action = NotificationCompat.Action.Builder(R.drawable.logo_transparent,
//                        roomsNotifications.mWearableMessage,
//                        stackBuilderTap.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
//                        .build()
//                wearableExtender.addAction(action)
//                builder.extend(wearableExtender)
//            } catch (e: Exception) {
//                Log.e(LOG_TAG, "## addTextStyleWithSeveralRooms() : WearableExtender failed " + e.message, e)
//            }
//        }
//    }

    /**
     * Add the notification sound.
     *
     * @param context      the context
     * @param builder      the notification builder
     * @param isBackground true if the notification is a background one (e.g. read receipt)
     * @param isBing       true if the notification should play sound
     */
    @SuppressLint("NewApi")
    private fun manageNotificationSound(context: Context, builder: NotificationCompat.Builder, isBackground: Boolean, isBing: Boolean) {
        @ColorInt val highlightColor = ContextCompat.getColor(context, R.color.vector_fuchsia_color)

        //set default, will be overridden if needed
        builder.color = Color.TRANSPARENT

        if (isBackground) { // no event notification (like read receipt)
            builder.priority = NotificationCompat.PRIORITY_LOW
            builder.setChannelId(SILENT_NOTIFICATION_CHANNEL_ID)
        } else {
            builder.setDefaults(Notification.DEFAULT_LIGHTS)

            if (isBing) {
                builder.setChannelId(NOISY_NOTIFICATION_CHANNEL_ID)
                builder.color = highlightColor
                //android <O compatibility, set priority and set the ringtone
                builder.priority = NotificationCompat.PRIORITY_DEFAULT
                if (null != PreferencesManager.getNotificationRingTone(context)) {
                    builder.setSound(PreferencesManager.getNotificationRingTone(context))
                }
            } else {
                builder.priority = NotificationCompat.PRIORITY_LOW
                builder.setChannelId(SILENT_NOTIFICATION_CHANNEL_ID)
            }

            // turn the screen on for 3 seconds
            if (Matrix.getInstance(VectorApp.getInstance())!!.pushManager.isScreenTurnedOn) {
                val pm = VectorApp.getInstance().getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "riot:manageNotificationSound")
                wl.acquire(3000)
                wl.release()
            }
        }
    }

    /**
     * Build a notification
     *
     * @param context            the context
     * @param roomsNotifications the rooms notifications
     * @param bingRule           the bing rule
     * @param isBackground       true if it is background notification (e.g. read receipt)
     * @return the notification
     */
//    private fun buildMessageNotification(context: Context,
//                                         roomsNotifications: RoomsNotifications,
//                                         bingRule: BingRule,
//                                         isBackground: Boolean): Notification? {
//        try {
//            var largeBitmap: Bitmap? = null
//
//            // when the event is an invitation one
//            // don't check if the sender ID is known because the members list are not yet downloaded
//            if (!roomsNotifications.mIsInvitationEvent) {
//                // is there any avatar url
//                if (!TextUtils.isEmpty(roomsNotifications.mRoomAvatarPath)) {
//                    val options = BitmapFactory.Options()
//                    options.inPreferredConfig = Bitmap.Config.ARGB_8888
//                    try {
//                        largeBitmap = BitmapFactory.decodeFile(roomsNotifications.mRoomAvatarPath, options)
//                    } catch (oom: OutOfMemoryError) {
//                        Log.e(LOG_TAG, "decodeFile failed with an oom", oom)
//                    }
//
//                }
//            }
//
//            Log.d(LOG_TAG, "prepareNotification : with sound " + BingRule.isDefaultNotificationSound(bingRule.notificationSound))
//
//            val builder = NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
//                    .setWhen(roomsNotifications.mContentTs)
//                    .setContentTitle(ensureTitleNotEmpty(context, roomsNotifications.mContentTitle))
//                    .setContentText(roomsNotifications.mContentText)
//                    .setSmallIcon(R.drawable.logo_transparent)
//                    .setGroup(context.getString(R.string.riot_app_name))
//                    .setGroupSummary(true)
//                    .setDeleteIntent(PendingIntent.getBroadcast(context.applicationContext,
//                            0, Intent(context.applicationContext, DismissNotificationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT))
//
//            try {
//                addTextStyle(context, builder, roomsNotifications)
//            } catch (e: Exception) {
//                Log.e(LOG_TAG, "## buildMessageNotification() : addTextStyle failed " + e.message, e)
//            }
//
//            // only one room : display the large bitmap (it should be the room avatar)
//            // several rooms : display the Riot avatar
//            if (roomsNotifications.mRoomNotifications.size == 1) {
//                if (null != largeBitmap) {
//                    builder.setLargeIcon(largeBitmap.createSquareBitmap())
//                }
//            }
//
//            manageNotificationSound(context, builder, isBackground, BingRule.isDefaultNotificationSound(bingRule.notificationSound))
//
//            return builder.build()
//        } catch (e: Exception) {
//            Log.e(LOG_TAG, "## buildMessageNotification() : failed" + e.message, e)
//        }
//
//        return null
//    }

    /**
     * Build a notification
     *
     * @param context         the context
     * @param messagesStrings the message texts
     * @param bingRule        the bing rule
     * @return the notification
     */
//    @Deprecated("will be removed")
//    fun buildMessagesListNotification(context: Context, messagesStrings: List<CharSequence>, bingRule: BingRule): Notification? {
//        try {
//
//            val builder = NotificationCompat.Builder(context, SILENT_NOTIFICATION_CHANNEL_ID)
//                    .setWhen(System.currentTimeMillis())
//                    .setContentTitle(context.getString(R.string.riot_app_name))
//                    .setContentText(messagesStrings[0])
//                    .setSmallIcon(R.drawable.logo_transparent)
//                    .setGroup(context.getString(R.string.riot_app_name))
//                    .setGroupSummary(true)
//
//            val inboxStyle = NotificationCompat.InboxStyle()
//
//            for (i in 0 until Math.min(RoomsNotifications.MAX_NUMBER_NOTIFICATION_LINES, messagesStrings.size)) {
//                inboxStyle.addLine(messagesStrings[i])
//            }
//
//            inboxStyle.setBigContentTitle(context.getString(R.string.riot_app_name))
//                    .setSummaryText(
//                            context.resources
//                                    .getQuantityString(R.plurals.notification_unread_notified_messages, messagesStrings.size, messagesStrings.size))
//
//            builder.setStyle(inboxStyle)
//
//            // open the home activity
//            val stackBuilderTap = TaskStackBuilder.create(context)
//            val roomIntentTap = Intent(context, VectorHomeActivity::class.java)
//            roomIntentTap.action = TAP_TO_VIEW_ACTION + System.currentTimeMillis().toInt()
//            stackBuilderTap.addNextIntent(roomIntentTap)
//
//            builder.setContentIntent(stackBuilderTap.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
//
//            manageNotificationSound(context, builder, false, BingRule.isDefaultNotificationSound(bingRule.notificationSound))
//
//            return builder.build()
//        } catch (e: Exception) {
//            Log.e(LOG_TAG, "## buildMessagesListNotification() : failed" + e.message, e)
//        }
//
//        return null
//    }

    fun buildMessagesListNotification(context: Context, messageSytle: NotificationCompat.MessagingStyle,
                                      roomInfo: RoomEventGroupInfo,
                                      largeIcon: Bitmap?,
                                      senderDisplayNameForReplyCompat: String?): Notification? {

        val accentColor = ThemeUtils.getColor(context, R.attr.colorAccent)
        // Build the pending intent for when the notification is clicked
        val openRoomIntent = buildOpenRoomIntent(context, roomInfo.roomId)
        val smallIcon = if (roomInfo.shouldBing) R.drawable.icon_notif_important else R.drawable.logo_transparent

        val channelID = if (roomInfo.shouldBing) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID
        val builder = NotificationCompat.Builder(context, channelID)
        builder.apply {
            // MESSAGING_STYLE sets title and content for API 16 and above devices.
            setStyle(messageSytle)

            // A category allows groups of notifications to be ranked and filtered – per user or system settings.
            // For example, alarm notifications should display before promo notifications, or message from known contact
            // that can be displayed in not disturb mode if white listed (the later will need compat28.x)
            setCategory(Notification.CATEGORY_MESSAGE)

            // Title for API < 16 devices.
            setContentTitle(roomInfo.roomDisplayName)
            // Content for API < 16 devices.
            setContentText("New Messages") //TODO

            // Number of new notifications for API <24 (M and below) devices.
            setSubText(context.resources.getQuantityString(R.plurals.room_new_messages_notification,messageSytle.messages.size,messageSytle.messages.size))

            // Auto-bundling is enabled for 4 or more notifications on API 24+ (N+)
            // devices and all Wear devices. But we want a custom grouping, so we specify the groupID
            setGroup(context.getString(R.string.riot_app_name))

            //In order to avoid notification making sound twice (due to the summary notificaiton)
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)

            setSmallIcon(smallIcon)

            // Set primary color (important for Wear 2.0 Notifications).
            color = accentColor

            // Sets priority for 25 and below. For 26 and above, 'priority' is deprecated for
            // 'importance' which is set in the NotificationChannel. The integers representing
            // 'priority' are different from 'importance', so make sure you don't mix them.
            priority = NotificationCompat.PRIORITY_DEFAULT
            if (roomInfo.shouldBing) {
                //Compat
                PreferencesManager.getNotificationRingTone(context)?.let {
                    setSound(it)
                }
                setLights(accentColor, 500, 500)
            } else {
                priority = NotificationCompat.PRIORITY_LOW
            }

            //Add actions and notification intents

            if (!roomInfo.hasSmartReplyError) {
                buildQuickReplyIntent(context, roomInfo.roomId, senderDisplayNameForReplyCompat)?.let { replyPendingIntent ->
                    var replyLabel: String = context.getString(R.string.action_quick_reply)
                    var remoteInput: RemoteInput = RemoteInput.Builder(NotificationBroadcastReceiver.KEY_TEXT_REPLY).run {
                        setLabel(replyLabel)
                        build()
                    }
                    NotificationCompat.Action.Builder(R.drawable.vector_notification_quick_reply,
                            context.getString(R.string.action_quick_reply), replyPendingIntent)
                            .addRemoteInput(remoteInput)
                            .build()?.let {
                                addAction(it)
                            }
                }
            }

            if (openRoomIntent != null) {
                setContentIntent(openRoomIntent)
            }

            if (largeIcon != null) {
                setLargeIcon(largeIcon)
            }

            val intent = Intent(context, NotificationBroadcastReceiver::class.java)
            intent.putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomInfo.roomId)
            intent.action = DISMISS_ROOM_NOTIF_ACTION
            val pendingIntent = PendingIntent.getBroadcast(context.applicationContext,
                    System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT)
            setDeleteIntent(pendingIntent)
        }
        return builder.build()
    }


    fun buildSimpleEventNotification(context: Context, simpleNotifiableEvent: NotifiableEvent, largeIcon: Bitmap?, matrixId: String): Notification? {

        val accentColor = ThemeUtils.getColor(context, R.attr.colorAccent)
        // Build the pending intent for when the notification is clicked
//        val openRoomIntent = buildOpenRoomIntent(context, roomInfo.roomId)
        val smallIcon = if (simpleNotifiableEvent.noisy) R.drawable.icon_notif_important else R.drawable.logo_transparent

        val builder = NotificationCompat.Builder(context, if (simpleNotifiableEvent.noisy) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID)
        builder.apply {
            setContentTitle(context.getString(R.string.riot_app_name))
            setContentText(simpleNotifiableEvent.description)
            //setSubText(roomInfo.roomDisplayName)
            //setNumber(messageSytle.messages.size)
            setGroup(context.getString(R.string.riot_app_name))
            setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            setSmallIcon(smallIcon)
            color = accentColor


            if (simpleNotifiableEvent is InviteNotifiableEvent) {
                val roomId = simpleNotifiableEvent.eventId
                // offer to type a quick reject button
                val rejectIntent = JoinRoomActivity.getRejectRoomIntent(context, roomId, matrixId)

                // the action must be unique else the parameters are ignored
                rejectIntent.action = REJECT_ACTION
                addAction(
                        R.drawable.vector_notification_reject_invitation,
                        context.getString(R.string.reject),
                        PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), rejectIntent, 0))

                // offer to type a quick accept button
                val joinIntent = JoinRoomActivity.getJoinRoomIntent(context, roomId, matrixId)

                // the action must be unique else the parameters are ignored
                joinIntent.action = JOIN_ACTION + System.currentTimeMillis().toInt()
                addAction(
                        R.drawable.vector_notification_accept_invitation,
                        context.getString(R.string.join),
                        PendingIntent.getActivity(context, 0, joinIntent, 0))

            } else {
                setAutoCancel(true)
            }

            val contentIntent = Intent(context, VectorHomeActivity::class.java)
            contentIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            //pending intent get reused by system, this will mess up the extra params, so put unique info to avoid that
            contentIntent.data = Uri.parse("foobar://"+simpleNotifiableEvent.eventId)
            setContentIntent(PendingIntent.getActivity(context, 0, contentIntent, 0))

            if (largeIcon != null) {
                setLargeIcon(largeIcon)
            }

            if (simpleNotifiableEvent.noisy) {
                //Compat
                priority = NotificationCompat.PRIORITY_DEFAULT
                PreferencesManager.getNotificationRingTone(context)?.let {
                    setSound(it)
                }
                setLights(accentColor, 500, 500)
            } else {
                priority = NotificationCompat.PRIORITY_LOW
            }
            setAutoCancel(true)
//            val intent = Intent(context, ReplyNotificationBroadcastReceiver::class.java)
//            intent.putExtra(ReplyNotificationBroadcastReceiver.KEY_ROOM_ID,roomInfo.roomId)
//            intent.action = DISMISS_ROOM_NOTIF_ACTION
//            val pendingIntent = PendingIntent.getBroadcast(context.applicationContext,
//                    System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT)
//            setDeleteIntent(pendingIntent)
        }
        return builder.build()
    }


    private fun buildOpenRoomIntent(context: Context, roomId: String): PendingIntent? {
        val roomIntentTap = Intent(context, VectorRoomActivity::class.java)
        roomIntentTap.putExtra(VectorRoomActivity.EXTRA_ROOM_ID, roomId)
        roomIntentTap.action = TAP_TO_VIEW_ACTION
        //pending intent get reused by system, this will mess up the extra params, so put unique info to avoid that
        roomIntentTap.data = Uri.parse("foobar://openRoom?$roomId")

        // Recreate the back stack
        val stackBuilderTap = TaskStackBuilder.create(context)
                .addNextIntentWithParentStack(Intent(context, VectorHomeActivity::class.java))
                .addNextIntent(roomIntentTap)
        return stackBuilderTap.getPendingIntent(System.currentTimeMillis().toInt(), PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildOpenHomePendingIntentForSummary(context: Context): PendingIntent {
        val intent = Intent(context, VectorHomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.putExtra(VectorHomeActivity.EXTRA_CLEAR_EXISTING_NOTIFICATION, true)
        intent.data = Uri.parse("foobar://tapSummary")
        return PendingIntent.getActivity(context, Random().nextInt(1000), intent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /*
        Direct reply is new in Android N, and Android already handles the UI, so the right pending intent
        here will ideally be a Service/IntentService (for a long running background task) or a BroadcastReceiver,
         which runs on the UI thread. It also works without unlocking, making the process really fluid for the user.
        However, for Android devices running Marshmallow and below (API level 23 and below),
        it will be more appropriate to use an activity. Since you have to provide your own UI.
     */
    private fun buildQuickReplyIntent(context: Context, roomId: String, senderName: String?): PendingIntent? {
        val intent: Intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            intent = Intent(context, NotificationBroadcastReceiver::class.java)
            intent.action = "${SMART_REPLY_ACTION}_$roomId"
            intent.putExtra(NotificationBroadcastReceiver.KEY_ROOM_ID, roomId)
            return PendingIntent.getBroadcast(context, System.currentTimeMillis().toInt(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            if (!LockScreenActivity.isDisplayingALockScreenActivity()) {
                // start your activity for Android M and below
                val quickReplyIntent = Intent(context, LockScreenActivity::class.java)
                quickReplyIntent.putExtra(LockScreenActivity.EXTRA_ROOM_ID, roomId)
                quickReplyIntent.putExtra(LockScreenActivity.EXTRA_SENDER_NAME, senderName ?: "")

                // the action must be unique else the parameters are ignored
                quickReplyIntent.action = QUICK_LAUNCH_ACTION + System.currentTimeMillis().toInt()
                return PendingIntent.getActivity(context, 0, quickReplyIntent, 0)
            }
        }
        return null
    }

    //// Number of new notifications for API <24 (M and below) devices.
    fun buildSummaryListNotification(context: Context, inboxSytle: NotificationCompat.InboxStyle, compatSummary: String, noisy: Boolean): Notification? {

        val smallIcon = if (noisy) R.drawable.icon_notif_important else R.drawable.logo_transparent

        val builder = NotificationCompat.Builder(context, if (noisy) NOISY_NOTIFICATION_CHANNEL_ID else SILENT_NOTIFICATION_CHANNEL_ID)
                .setStyle(inboxSytle) // used in compat < N, after summary is built based on child notifications
                .setContentTitle(context.getString(R.string.riot_app_name))
                .setSmallIcon(smallIcon)
                //set content text to support devices running API level < 24
                .setContentText(compatSummary)
                .setGroup(context.getString(R.string.riot_app_name))
                //set this notification as the summary for the group
                .setGroupSummary(true)
                .setColor(ThemeUtils.getColor(context, R.attr.colorAccent))
                .apply {
                    if (noisy) {
                        //Compat
                        priority = NotificationCompat.PRIORITY_DEFAULT
                        PreferencesManager.getNotificationRingTone(context)?.let {
                            setSound(it)
                        }
                    } else {
                        //compat
                        priority = NotificationCompat.PRIORITY_LOW
                    }

                    setContentIntent(buildOpenHomePendingIntentForSummary(context))
                    setDeleteIntent(getDismissSummaryPendingIntent(context))

                }
        return builder.build()

    }

    private fun getDismissSummaryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationBroadcastReceiver::class.java)
        intent.action = DISMISS_SUMMARY_ACTION
        intent.data = Uri.parse("foobar://deleteSummary")
        val pendingIntent = PendingIntent.getBroadcast(context.applicationContext,
                0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        return pendingIntent
    }

    /**
     * Show a notification containing messages
     */
    fun showNotificationMessage(context: Context, notification: Notification) {
        with(NotificationManagerCompat.from(context)) {
            notify(NOTIFICATION_ID_MESSAGES, notification)
        }
    }

    fun showNotificationMessage(context: Context, tag: String?, id: Int, notification: Notification) {
        with(NotificationManagerCompat.from(context)) {
            notify(tag, id, notification)
        }
    }


//    fun showSummaryNotificationMessage(context: Context, tag: String, notification: Notification) {
//        with(NotificationManagerCompat.from(context)) {
//            notify(tag,NOTIFICATION_ID_ROOM_MESSAGES,notification)
//        }
//    }

    /**
     * Cancel the notification containing messages
     */
    @Deprecated("wip")
    fun cancelNotificationMessage(context: Context) {
        NotificationManagerCompat.from(context)
                .cancel(NOTIFICATION_ID_MESSAGES)
    }

    fun cancelNotificationMessage(context: Context, tag: String?, id: Int) {
        NotificationManagerCompat.from(context)
                .cancel(tag, id)
    }

    /**
     * Cancel the foreground notification service
     */
    fun cancelNotificationForegroundService(context: Context) {
        NotificationManagerCompat.from(context)
                .cancel(NOTIFICATION_ID_FOREGROUND_SERVICE)
    }

    /**
     * Cancel all the notification
     */
    fun cancelAllNotifications(context: Context) {
        // Keep this try catch (reported by GA)
        try {
            NotificationManagerCompat.from(context)
                    .cancelAll()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "## cancelAllNotifications() failed " + e.message, e)
        }
    }

    /**
     * Return true it the user has enabled the do not disturb mode
     */
    fun isDoNotDisturbModeOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }

        // We cannot use NotificationManagerCompat here.
        val setting = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter

        return setting == NotificationManager.INTERRUPTION_FILTER_NONE
                || setting == NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun ensureTitleNotEmpty(context: Context, title: String?): CharSequence {
        if (TextUtils.isEmpty(title)) {
            return context.getString(R.string.app_name)
        }

        return title!!
    }

    fun openSystemSettingsForSilentCategory(fragment: Fragment) {
        startNotificationChannelSettingsIntent(fragment, SILENT_NOTIFICATION_CHANNEL_ID)
    }

    fun openSystemSettingsForNoisyCategory(fragment: Fragment) {
        startNotificationChannelSettingsIntent(fragment, NOISY_NOTIFICATION_CHANNEL_ID)
    }


    fun openSystemSettingsForCallCategory(fragment: Fragment) {
        startNotificationChannelSettingsIntent(fragment, CALL_NOTIFICATION_CHANNEL_ID)
    }
}
