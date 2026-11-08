package com.prajwal.cardashboard;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/**
 * Android's answer to CarLinkInstaller.install: first "the user must confirm" (its own install screen, opened here),
 * then installed, or failed with a reason.
 */
public class CarLinkInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "CarDash.CarLinkInstall";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = readConfirmIntent(intent);
            if (confirm == null) {
                Log.w(TAG, "installer asked for confirmation without a screen to show");
                AutoPlay.onInstallerAnswered();
                Toast.makeText(context, R.string.autoplay_install_failed_plain, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Log.w(TAG, "could not show the install screen", e);
                AutoPlay.onInstallerAnswered();
                Toast.makeText(context, R.string.autoplay_install_failed_plain, Toast.LENGTH_LONG).show();
            }
            return;
        }
        AutoPlay.onInstallerAnswered();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "CarLink installed");
            CarLinkUpdateCheck.cancelNotification(context);
            Toast.makeText(context, R.string.autoplay_installed, Toast.LENGTH_LONG).show();
            return;
        }
        String reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.w(TAG, "CarLink not installed: status " + status + ", " + reason);
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) return;   // the user said no
        Toast.makeText(context, context.getString(R.string.autoplay_install_failed, reason != null ? reason : "?"),
                Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private static Intent readConfirmIntent(Intent intent) {
        return Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                : intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
