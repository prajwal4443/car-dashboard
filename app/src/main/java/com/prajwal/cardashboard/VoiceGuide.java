package com.prajwal.cardashboard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Navigation's voice (TripGuide's turns): Android's text to speech as navigation guidance, music lowered while it
 * speaks (audio focus, may duck). Muted or not is kept between trips (the speaker button on the navigation screen).
 */
final class VoiceGuide {
    private static final String TAG = "CarDash.Voice";
    private static final String PREFS = "navigation";
    private static final String MUTED_KEY = "voice_muted";
    private static final AudioAttributes GUIDANCE = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private static TextToSpeech voice;
    private static boolean ready;
    /** Said once the speech engine is ready (the first words of a trip come before that). */
    private static String waiting;
    private static AudioManager audio;
    private static AudioFocusRequest focus;

    private VoiceGuide() {
    }

    static boolean isMuted(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MUTED_KEY, false);
    }

    /** Mutes (cutting off anything being said) or unmutes the voice. */
    static void saveMuted(Context context, boolean muted) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(MUTED_KEY, muted).apply();
        if (muted) stopSpeaking();
    }

    /** Says {@code text} now, over anything still being said; nothing while muted. */
    static void speak(Context context, String text) {
        if (isMuted(context)) return;
        Context app = context.getApplicationContext();
        if (voice == null) {
            audio = app.getSystemService(AudioManager.class);
            voice = new TextToSpeech(app, status -> {
                ready = status == TextToSpeech.SUCCESS;
                if (!ready) {
                    Log.w(TAG, "no speech engine");
                    return;
                }
                Locale indian = new Locale("en", "IN");
                if (voice.isLanguageAvailable(indian) < TextToSpeech.LANG_AVAILABLE) indian = Locale.UK;
                voice.setLanguage(indian);
                voice.setAudioAttributes(GUIDANCE);
                voice.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        releaseFocus();
                    }

                    @Override
                    @SuppressWarnings("deprecation")
                    public void onError(String utteranceId) {
                        releaseFocus();
                    }
                });
                if (waiting != null) say(waiting);
                waiting = null;
            });
        }
        if (ready) {
            say(text);
        } else {
            waiting = text;
        }
    }

    private static void say(String text) {
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(GUIDANCE).build();
        }
        audio.requestAudioFocus(focus);
        voice.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "navigation");
        Log.i(TAG, "said: " + text);
    }

    static void stopSpeaking() {
        waiting = null;
        if (voice != null) voice.stop();
        releaseFocus();
    }

    private static void releaseFocus() {
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    }
}
