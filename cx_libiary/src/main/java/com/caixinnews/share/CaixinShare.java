package com.caixinnews.share;


import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.widget.Toast;

import com.sina.weibo.sdk.api.ImageObject;
import com.sina.weibo.sdk.api.TextObject;
import com.sina.weibo.sdk.api.WeiboMultiMessage;
import com.sina.weibo.sdk.api.share.IWeiboShareAPI;
import com.sina.weibo.sdk.api.share.SendMultiMessageToWeiboRequest;
import com.sina.weibo.sdk.api.share.WeiboShareSDK;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.auth.WeiboAuthListener;
import com.sina.weibo.sdk.exception.WeiboException;
import com.sina.weibo.sdk.net.RequestListener;
import com.sina.weibo.sdk.openapi.StatusesAPI;
import com.tencent.connect.share.QQShare;
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.modelmsg.WXImageObject;
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage;
import com.tencent.mm.opensdk.modelmsg.WXTextObject;
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;
import com.tencent.open.utils.ThreadManager;
import com.tencent.tauth.IUiListener;
import com.tencent.tauth.Tencent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CaixinShare {
    public static final String SHARE_PLATFORM_WEICHAT = "Wechat";
    public static final String SHARE_PLATFORM_MOMENT = "WechatMoments";
    public static final String SHARE_PLATFORM_QQ = "QQ";
    public static final String SHARE_PLATFORM_WEIBO = "SinaWeibo";
    public static final String SHARE_PLATFORM_EMAIL = "Email";


    private Activity context;
    private boolean isInstalledWeibo;
    private int supportApiLevel;

    public CaixinShare(Activity activity) {
        this.context = activity;
    }

    public static void init(String APP_ID_WX, String APP_WX_SECRET, String APP_ID_QQ, String APP_KEY_WEIBO, String REDIRECT_URL_WEIBO, String SCOPE_WEIBO) {
        Constants.APP_ID = APP_ID_WX;
        Constants.APP_WECHAT_SECRET = APP_WX_SECRET;
        Constants.APP_ID_QQ = APP_ID_QQ;
        Constants.APP_KEY_WEIBO = APP_KEY_WEIBO;
        Constants.REDIRECT_URL_WEIBO = REDIRECT_URL_WEIBO;
        Constants.SCOPE_WEIBO = SCOPE_WEIBO;
    }

    private static final int THUMB_SIZE = 150;

    public void shareToPlatform(CXShareEntity entity, ICXShareCallback qqShareListener) {
        switch (entity.platform) {
            case SHARE_PLATFORM_WEICHAT:
            case SHARE_PLATFORM_MOMENT:
                shareToWeiChat(entity);
                break;
            case SHARE_PLATFORM_WEIBO:
                shareToWeibo(entity);
                break;
            case SHARE_PLATFORM_QQ:
                shareToQQ(entity, qqShareListener);
                break;
            case SHARE_PLATFORM_EMAIL:
                shareToEmail(entity);
                break;
        }
    }

    /**
     * 分享到微信聊天、微信朋友圈
     *
     * @param entity
     */
    public void shareToWeiChat(CXShareEntity entity) {
        if (!Util.isWeixinAvilible(context)) {
            return;
        }
        // 获取IWXAPI的实例
        IWXAPI api = WXAPIFactory.createWXAPI(context, Constants.APP_ID, false);

        int mTargetScene = SendMessageToWX.Req.WXSceneSession;
        if (SHARE_PLATFORM_MOMENT.equals(entity.platform)) {
            mTargetScene = SendMessageToWX.Req.WXSceneTimeline;
        }

        // 将该app注册到微信
        api.registerApp(Constants.APP_ID);


        if (!TextUtils.isEmpty(entity.url) && !TextUtils.isEmpty(entity.title)) {//网页分享
            WXWebpageObject webpage = new WXWebpageObject();
            webpage.webpageUrl = entity.url;

            WXMediaMessage msg = new WXMediaMessage(webpage);
            msg.title = entity.title;
            msg.description = entity.summary;

            Bitmap bmp = BitmapFactory.decodeFile(entity.imagePath);
            try {
                Bitmap thumbBmp = Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE, true);
                bmp.recycle();
                msg.thumbData = Util.bmpToByteArray(thumbBmp, true);
            } catch (NullPointerException e) {
                e.printStackTrace();
            }

            SendMessageToWX.Req req = new SendMessageToWX.Req();
            req.transaction = buildTransaction("webpage");
            req.message = msg;
            req.scene = mTargetScene;
            api.sendReq(req);
        } else if (!TextUtils.isEmpty(entity.imagePath)) {//图片分享

            WXImageObject imgObj = new WXImageObject();
            imgObj.setImagePath(entity.imagePath);

            WXMediaMessage msg = new WXMediaMessage();
            msg.mediaObject = imgObj;


            Bitmap bmp = BitmapFactory.decodeFile(entity.imagePath);
            try {
                Bitmap thumbBmp = Bitmap.createScaledBitmap(bmp, THUMB_SIZE, THUMB_SIZE, true);
                bmp.recycle();
                msg.thumbData = Util.bmpToByteArray(thumbBmp, true);
            } catch (NullPointerException e) {
                e.printStackTrace();
            }

            SendMessageToWX.Req req = new SendMessageToWX.Req();
            req.transaction = buildTransaction("img");
            req.message = msg;
            req.scene = mTargetScene;
            api.sendReq(req);
        } else if (!TextUtils.isEmpty(entity.title)) {//分享纯文字
            WXTextObject textObj = new WXTextObject();
            textObj.text = entity.title;

            WXMediaMessage msg = new WXMediaMessage();
            msg.mediaObject = textObj;
            msg.description = entity.summary;

            SendMessageToWX.Req req = new SendMessageToWX.Req();
            req.transaction = buildTransaction("text");
            req.message = msg;
            req.scene = mTargetScene;

            api.sendReq(req);
        } else {
            Toast.makeText(context, "数据错误", Toast.LENGTH_LONG).show();
        }
    }


    int shareType = -1;//qq分享类别

    public void shareToQQ(CXShareEntity entity, IUiListener qqShareListener) {
        if (!Util.isQQClientAvailable(context)) {
            return;
        }
        Tencent mTencent = Tencent.createInstance(Constants.APP_ID_QQ, context);

        final Bundle params = new Bundle();

        if (TextUtils.isEmpty(entity.url) && !TextUtils.isEmpty(entity.imagePath)) {//url为空，imagepath不为空则认为图片分享
            shareType = QQShare.SHARE_TO_QQ_TYPE_IMAGE;
        } else if (!TextUtils.isEmpty(entity.title)) {
            shareType = QQShare.SHARE_TO_QQ_TYPE_DEFAULT;
        }

        if (shareType != QQShare.SHARE_TO_QQ_TYPE_IMAGE) {
            params.putString(QQShare.SHARE_TO_QQ_TITLE, entity.title);
            params.putString(QQShare.SHARE_TO_QQ_TARGET_URL, entity.url);
            params.putString(QQShare.SHARE_TO_QQ_SUMMARY, entity.summary);
        }
        params.putString(shareType == QQShare.SHARE_TO_QQ_TYPE_IMAGE ? QQShare.SHARE_TO_QQ_IMAGE_LOCAL_URL
                : QQShare.SHARE_TO_QQ_IMAGE_URL, entity.imagePath);

        params.putString(QQShare.SHARE_TO_QQ_APP_NAME, "AppName");
        params.putInt(QQShare.SHARE_TO_QQ_KEY_TYPE, shareType);
//        params.putInt(QQShare.SHARE_TO_QQ_EXT_INT, QQShare.SHARE_TO_QQ_FLAG_QZONE_AUTO_OPEN);
        doShareToQQ(params, mTencent, qqShareListener);
    }

    private void doShareToQQ(final Bundle params, final Tencent mTencent, final IUiListener qqShareListener) {
        // QQ分享要在主线程做
        ThreadManager.getMainHandler().post(new Runnable() {

            @Override
            public void run() {
                if (null != mTencent) {
                    mTencent.shareToQQ((Activity) context, params, qqShareListener);
                }
            }
        });
    }

    /**
     * QQ登录或分享时，需要在 相应activity的onActivityResult()} 中调用，否则回调中方法不执行
     */
    public static void onActivityResultForQQ(int requestCode, int resultCode,
                                             Intent data, IUiListener uiListener) {
        if (requestCode == com.tencent.connect.common.Constants.REQUEST_QQ_SHARE) {
            Tencent.onActivityResultData(requestCode, resultCode, data, uiListener);
        }
    }

    /**
     * 选择
     */
    public void shareDependsPlate(CXShareEntity entity) {
        String emailSubject = entity.title;
        String emailBody = entity.summary;
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        List<ResolveInfo> resInfo = context.getPackageManager().queryIntentActivities(it, 0);
        if (!resInfo.isEmpty()) {
            List<Intent> recommonedIntents = new ArrayList<Intent>();
            for (ResolveInfo info : resInfo) {
                Intent targeted = new Intent(Intent.ACTION_SEND);
                targeted.setType("text/plain");
                targeted.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityInfo activityInfo = info.activityInfo;
                if (activityInfo.packageName.toLowerCase().contains("mail") || activityInfo.name.toLowerCase().contains("mail")) {
                    targeted.putExtra(android.content.Intent.EXTRA_SUBJECT, emailSubject);
                    targeted.putExtra(android.content.Intent.EXTRA_TEXT, emailBody);
                    recommonedIntents.add(targeted);
                } else {
                    continue;
                }

                targeted.setComponent(new ComponentName(activityInfo.packageName, activityInfo.name));
                recommonedIntents.add(targeted);

            }
            if (recommonedIntents.size() < 1) {
                return;
            }
            Intent chooserIntent = Intent.createChooser(recommonedIntents.remove(recommonedIntents.size() - 1), "请选择邮件应用");
            if (chooserIntent == null) {
                return;
            }
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, recommonedIntents.toArray(new Parcelable[]{}));
            try {
                context.startActivity(chooserIntent);
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(context, "未找到邮件应用", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void shareToEmail(CXShareEntity entity) {
        if (!TextUtils.isEmpty(entity.imagePath)) {//&&!TextUtils.isEmpty(entity.title)
            Intent email = new Intent(android.content.Intent.ACTION_SEND);
            File file = new File(entity.imagePath);
            email.setType("application/octet-stream");
            String emailTitle = entity.title;
            String emailContent = entity.summary;
            email.putExtra(android.content.Intent.EXTRA_SUBJECT, emailTitle);
            email.putExtra(android.content.Intent.EXTRA_TEXT, emailContent);
            email.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
            //调用系统的邮件系统
//            context.startActivity(Intent.createChooser(email, "请选择邮件发送软件"));
            filterApp(email, entity, 1);
        } else if (TextUtils.isEmpty(entity.imagePath)) {
            Intent email = new Intent(android.content.Intent.ACTION_SEND);
            email.setType("plain/text");
            String emailTitle = entity.title;
            String emailContent = entity.summary;
            email.putExtra(android.content.Intent.EXTRA_SUBJECT, emailTitle);
            email.putExtra(android.content.Intent.EXTRA_TEXT, emailContent);
//            context.startActivity(Intent.createChooser(email, "请选择邮件发送软件"));
            filterApp(email, entity, 0);
        }

    }


    private void filterApp(Intent intent, CXShareEntity entity, int type) {//type:0纯文字1带附件

        List<ResolveInfo> resInfo = context.getPackageManager().queryIntentActivities(intent, 0);
        if (!resInfo.isEmpty()) {
            List<Intent> recommonedIntents = new ArrayList<Intent>();
            for (ResolveInfo info : resInfo) {
                Intent targeted = new Intent(Intent.ACTION_SEND);
                if (type == 1) {
                    targeted.setType("application/octet-stream");
                } else {
                    targeted.setType("text/plain");
                }
                targeted.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ActivityInfo activityInfo = info.activityInfo;
                if (activityInfo.packageName.contains("mail") || activityInfo.name.contains("mail")) {
                    targeted.putExtra(android.content.Intent.EXTRA_SUBJECT, entity.title);
                    targeted.putExtra(android.content.Intent.EXTRA_TEXT, entity.summary);
                    targeted.setComponent(new ComponentName(activityInfo.packageName, activityInfo.name));
                    if (type == 1) {
                        File file = new File(entity.imagePath);
                        targeted.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file));
                    }
                    recommonedIntents.add(targeted);
                } else {
                    continue;
                }

            }
            if (recommonedIntents.size() < 1) {
                return;
            } else if (recommonedIntents.size() == 1) {
                context.startActivity(recommonedIntents.get(0));
            } else {
                Intent chooserIntent = Intent.createChooser(recommonedIntents.remove(recommonedIntents.size() - 1), "分享到");
                if (chooserIntent == null) {
                    return;
                }
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, recommonedIntents.toArray(new Parcelable[]{}));
                try {
                    context.startActivity(chooserIntent);
                } catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(context, "未找到邮件应用", Toast.LENGTH_SHORT).show();
                }
            }

        }
    }

    private String buildTransaction(final String type) {
        return (type == null) ? String.valueOf(System.currentTimeMillis()) : type + System.currentTimeMillis();
    }

    private CXShareEntity entity;
    private Oauth2AccessToken mAccessToken;

    public void shareToWeibo(CXShareEntity entity) {
        //将APP注册到微博
        registerAppToWeibo();
        this.entity = entity;
        //分享
        if (isInstalledWeibo) {
            share_Weibo_client(entity);
        } else {
            //判断是否需要通过web授权
            long expiretime = AccessTokenKeeper.readAccessToken(context).getExpiresTime();
            long currenttime = System.currentTimeMillis();
            if (expiretime - currenttime < 0) {
                auth();
            } else {
                shareToWeiboFromWeb(entity);
            }
        }

    }

    private void auth() {
        CaixinLogin caixinLogin = new CaixinLogin(context);
        caixinLogin.LoginFromWeiboWeb(listener);
    }

    public void shareToWeiboFromWeb(CXShareEntity entity) {
        if (mAccessToken == null) {
            mAccessToken = AccessTokenKeeper.readAccessToken(context);
        }
        StatusesAPI statusesAPI = new StatusesAPI(context, Constants.APP_KEY_WEIBO, mAccessToken);
        if (!TextUtils.isEmpty(entity.imagePath)) {
            //图片
            Bitmap bitmap = BitmapFactory.decodeFile(entity.imagePath);
            statusesAPI.upload(entity.summary, bitmap, "0.0", "0.0", requestListener);
        } else {
            //纯文本
            statusesAPI.update(entity.summary, "0.0", "0.0", requestListener);
        }
        Toast.makeText(context, "分享正在后台进行", Toast.LENGTH_LONG).show();

    }

    WeiboAuthListener listener = new WeiboAuthListener() {
        @Override
        public void onComplete(Bundle bundle) {
            // 从 Bundle 中解析 Token
            mAccessToken = Oauth2AccessToken.parseAccessToken(bundle);
            if (mAccessToken.isSessionValid()) {
                // 保存 Token 到 SharedPreferences
                AccessTokenKeeper.writeAccessToken(context, mAccessToken);
                if (entity != null) {
                    shareToWeiboFromWeb(entity);
                }
            } else {
                Toast.makeText(context, "授权失败", Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onWeiboException(WeiboException e) {

        }

        @Override
        public void onCancel() {
            Toast.makeText(context, "取消授权", Toast.LENGTH_LONG).show();
        }
    };

    RequestListener requestListener = new RequestListener() {
        @Override
        public void onComplete(String s) {
            Toast.makeText(context, "分享成功", Toast.LENGTH_LONG).show();
        }

        @Override
        public void onWeiboException(WeiboException e) {
            Toast.makeText(context, "分享失败", Toast.LENGTH_LONG).show();
        }
    };

    private void share_Weibo_client(CXShareEntity entity) {
        // 1. 初始化微博的分享消息
        WeiboMultiMessage weiboMessage = new WeiboMultiMessage();
        if (!TextUtils.isEmpty(entity.summary)) {
            TextObject textObject = new TextObject();
            textObject.text = entity.summary;
            weiboMessage.textObject = textObject;
        }
        if (!TextUtils.isEmpty(entity.imagePath)) {
            Bitmap bitmap = BitmapFactory.decodeFile(entity.imagePath);
            ImageObject imageObject = new ImageObject();
            imageObject.setImageObject(bitmap);
            weiboMessage.imageObject = imageObject;
        }
        // 2. 初始化从第三方到微博的消息请求
        SendMultiMessageToWeiboRequest request = new SendMultiMessageToWeiboRequest();
        // 用transaction唯一标识一个请求
        request.transaction = String.valueOf(System.currentTimeMillis());
        request.multiMessage = weiboMessage;
        // 3. 发送请求消息到微博，唤起微博分享界面
        mWeiboShareAPI.sendRequest((Activity) context, request);
    }

    private IWeiboShareAPI mWeiboShareAPI;

    private void registerAppToWeibo() {
        // 创建微博 SDK 接口实例
        mWeiboShareAPI = WeiboShareSDK.createWeiboAPI(context, Constants.APP_KEY_WEIBO);
        // 获取微博客户端相关信息，如是否安装、支持 SDK 的版本
        isInstalledWeibo = mWeiboShareAPI.isWeiboAppInstalled();
        supportApiLevel = mWeiboShareAPI.getWeiboAppSupportAPI();   //TODO 如何进行判断
        // 注册到新浪微博
        mWeiboShareAPI.registerApp();
    }

//    public void shareToFaceBook(CXShareEntity entity, final ICXShareCallback callback){
//        CallbackManager callBackManager  = CallbackManager.Factory.create();
//        FaceBookShareUtils fb = new FaceBookShareUtils((Activity)context,callBackManager,new FacebookCallback() {
//
//            @Override
//            public void onSuccess(Object o) {
////                Message msg = Message.obtain();
////                msg.what = SHARE_COMPLETE;
////                mHandler.sendMessage(msg);
////                Toast.makeText(context,"facebook 分享成功",Toast.LENGTH_LONG).show();
//                callback.onSuccess(o);
//            }
//
//            @Override
//            public void onCancel() {
////                Message msg = Message.obtain();
////                msg.what = SHARE_CANCEL;
////                mHandler.sendMessage(msg);
////                Toast.makeText(context,"facebook 分享取消",Toast.LENGTH_LONG).show();
//                callback.onCancel();
//            }
//
//            @Override
//            public void onError(FacebookException error) {
////                Message msg = Message.obtain();
////                msg.what = SHARE_ERROR;
////                mHandler.sendMessage(msg);
////                Toast.makeText(context,"facebook 分享失败",Toast.LENGTH_LONG).show();
//                callback.onError(error);
//            }
//        });
//        fb.share(entity.title,entity.imagePath,entity.summary);
//    }

}
