package com.pyler.xinternalsd;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XInternalSD implements IXposedHookZygoteInit,
		IXposedHookLoadPackage {
	public XSharedPreferences prefs;
	public String internalSd;
	public XC_MethodHook getExternalStorageDirectoryHook;
	public XC_MethodHook getExternalFilesDirHook;
	public XC_MethodHook getObbDirHook;
	public XC_MethodHook getDownloadDirHook;
	public XC_MethodHook externalSdCardAccessHook;

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		prefs = new XSharedPreferences(XInternalSD.class.getPackage().getName());
		prefs.makeWorldReadable();

		getExternalStorageDirectoryHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param)
					throws Throwable {
				File path = (File) param.getResult();
				String customInternalSd = path.toString().replaceFirst(
						internalSd, getCustomInternalSdPath());
				File customInternalSdPath = new File(customInternalSd);
				param.setResult(customInternalSdPath);
			}

		};

		getExternalFilesDirHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param)
					throws Throwable {
				String arg = (String) param.args[0];
				boolean isAppFilesDir = (arg == null);
				File path = (File) param.getResult();
				String appFilesDir = path.toString().replaceFirst(internalSd,
						getCustomInternalSdPath());
				File appFilesDirPath = new File(appFilesDir);
				if (isAppFilesDir) {
					param.setResult(appFilesDirPath);
				}
			}

		};

		getObbDirHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param)
					throws Throwable {
				File path = (File) param.getResult();
				String obbDir = path.toString().replaceFirst(internalSd,
						getCustomInternalSdPath());
				File obbDirPath = new File(obbDir);
				param.setResult(obbDirPath);
			}

		};

		getDownloadDirHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param)
					throws Throwable {
				prefs.reload();
				boolean changeDownloadDirPath = prefs.getBoolean(
						"change_download_path", true);
				String type = (String) param.args[0];
				boolean isDownloadDir = Environment.DIRECTORY_DOWNLOADS
						.equals(type);
				File path = (File) param.getResult();
				String downloadDir = path.toString().replaceFirst(internalSd,
						getCustomInternalSdPath());
				File downloadDirPath = new File(downloadDir);
				if (isDownloadDir && changeDownloadDirPath) {
					param.setResult(downloadDirPath);
				}
			}

		};

		externalSdCardAccessHook = new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param)
					throws Throwable {
				prefs.reload();
				String permission = (String) param.args[1];
				boolean sdCardFullAccess = prefs.getBoolean(
						"sdcard_full_access", true);
				if (sdCardFullAccess
						&& (permission
								.equals("android.permission.WRITE_EXTERNAL_STORAGE") || permission
								.equals("android.permission.ACCESS_ALL_EXTERNAL_STORAGE"))) {
					Class<?> process = XposedHelpers.findClass(
							"android.os.Process", null);
					int gid = (Integer) XposedHelpers.callStaticMethod(process,
							"getGidForName", "media_rw");
					Object settings = XposedHelpers.getObjectField(
							param.thisObject, "mSettings");
					Object permissions = XposedHelpers.getObjectField(settings,
							"mPermissions");
					Object bp = XposedHelpers.callMethod(permissions, "get",
							permission);
					int[] bpGids = (int[]) XposedHelpers.getObjectField(bp,
							"gids");
					XposedHelpers.setObjectField(bp, "gids",
							appendInt(bpGids, gid));
				}
			}
		};

		internalSd = Environment.getExternalStorageDirectory().toString();
		XposedHelpers.findAndHookMethod(XposedHelpers.findClass(
				"com.android.server.pm.PackageManagerService", null),
				"readPermission", "org.xmlpull.v1.XmlPullParser", String.class,
				externalSdCardAccessHook);
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!isAppEnabled(lpparam.appInfo)) {
			return;
		}

		XposedHelpers.findAndHookMethod(Environment.class,
				"getExternalStorageDirectory", getExternalStorageDirectoryHook);
		XposedHelpers.findAndHookMethod(XposedHelpers.findClass(
				"android.app.ContextImpl", lpparam.classLoader),
				"getExternalFilesDir", String.class, getExternalFilesDirHook);
		XposedHelpers.findAndHookMethod(XposedHelpers.findClass(
				"android.app.ContextImpl", lpparam.classLoader), "getObbDir",
				getObbDirHook);
		XposedHelpers.findAndHookMethod(Environment.class,
				"getExternalStoragePublicDirectory", String.class,
				getDownloadDirHook);
	}

	public boolean isAppEnabled(ApplicationInfo appInfo) {
		boolean isAppEnabled = true;
		prefs.reload();
		boolean moduleEnabled = prefs.getBoolean("custom_internal_sd", true);
		if (!moduleEnabled) {
			return false;
		}
		if (appInfo == null) {
			return false;
		}
		if (!isAllowedApp(appInfo)) {
			return false;
		}
		String packageName = appInfo.packageName;
		boolean enabledForAllApps = prefs.getBoolean("enable_for_all_apps",
				true);
		if (enabledForAllApps) {
			Set<String> disabledApps = prefs.getStringSet("disable_for_apps",
					new HashSet<String>());
			if (!disabledApps.isEmpty()) {
				isAppEnabled = !disabledApps.contains(packageName);
			}
		} else {
			Set<String> enabledApps = prefs.getStringSet("enable_for_apps",
					new HashSet<String>());
			if (!enabledApps.isEmpty()) {
				isAppEnabled = enabledApps.contains(packageName);
			} else {
				isAppEnabled = false;
			}
		}
		return isAppEnabled;

	}

	@SuppressLint("SdCardPath")
	public String getCustomInternalSdPath() {
		prefs.reload();
		String customInternalSd = prefs
				.getString("internal_sd_path", "/sdcard");
		return customInternalSd;
	}

	public boolean isAllowedApp(ApplicationInfo appInfo) {
		boolean isAllowedApp = false;
		if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 1) {
			isAllowedApp = true;
		}
		if ((appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
			isAllowedApp = true;
		}
		return isAllowedApp;
	}

	public int[] appendInt(int[] cur, int val) {
		if (cur == null) {
			return new int[] { val };
		}
		final int N = cur.length;
		for (int i = 0; i < N; i++) {
			if (cur[i] == val) {
				return cur;
			}
		}
		int[] ret = new int[N + 1];
		System.arraycopy(cur, 0, ret, 0, N);
		ret[N] = val;
		return ret;
	}
}