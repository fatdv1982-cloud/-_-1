package com.example.snake;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/**
 * Синтезатор звуков без файлов.
 * Генерирует PCM-волны прямо в памяти и проигрывает через AudioTrack.
 */
public class SoundManager {

    private static final int SAMPLE_RATE = 44100;
    private final Context context;
    private boolean soundOn = true;

    // Пул AudioTrack для параллельного воспроизведения (еда + ускорение)
    private final java.util.List<AudioTrack> activeTracks = new java.util.ArrayList<>();

    public SoundManager(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void setSoundOn(boolean on) { soundOn = on; }
    public boolean isSoundOn() { return soundOn; }
    public void toggle() { soundOn = !soundOn; }

    // ==================== ПУБЛИЧНЫЕ ЗВУКИ ====================

    /** Короткий "дзынь" при поедании еды — восходящий тон */
    public void playEat() {
        if (!soundOn) return;
        // Быстрый свип вверх: 600 → 1200 Гц за 90 мс
        playTone(new int[]{660, 880, 1100}, 90, 0.35f, WaveType.SINE);
    }

    /** "Бонус" — повышение уровня скорости */
    public void playSpeedUp() {
        if (!soundOn) return;
        // Арпеджио вверх
        playTone(new int[]{523, 659, 784, 1046}, 60, 0.3f, WaveType.TRIANGLE);
    }

    /** Проигрыш — нисходящий "сдув" */
    public void playGameOver() {
        if (!soundOn) return;
        // Долгий нисходящий тон
        playTone(new int[]{880, 660, 440, 220, 110}, 140, 0.45f, WaveType.SQUARE);
    }

    /** Щелчок смены направления (тихий) */
    public void playTurn() {
        if (!soundOn) return;
        playTone(new int[]{400}, 25, 0.12f, WaveType.SINE);
    }

    /** Старт игры — короткий аккорд */
    public void playStart() {
        if (!soundOn) return;
        playTone(new int[]{523, 659, 784}, 80, 0.3f, WaveType.TRIANGLE);
    }

    // ==================== ДВИЖОК СИНТЕЗА ====================

    private enum WaveType { SINE, SQUARE, TRIANGLE, SAW }

    /**
     * Проигрывает последовательность нот.
     * @param freqs   частоты (Гц)
     * @param msPerNote длительность каждой ноты (мс)
     * @param volume  громкость 0..1
     * @param type    форма волны
     */
    private void playTone(int[] freqs, int msPerNote, float volume, WaveType type) {
        int samplesPerNote = SAMPLE_RATE * msPerNote / 1000;
        int totalSamples = samplesPerNote * freqs.length;
        short[] buffer = new short[totalSamples];

        for (int n = 0; n < freqs.length; n++) {
            double freq = freqs[n];
            for (int i = 0; i < samplesPerNote; i++) {
                double t = (double) i / SAMPLE_RATE;
                double phase = 2 * Math.PI * freq * t;
                double sample = waveform(phase, type);

                // Огибающая (envelope): атака + спад, чтобы не было щелчков
                double env = envelope(i, samplesPerNote);

                int idx = n * samplesPerNote + i;
                buffer[idx] = (short) (sample * env * volume * Short.MAX_VALUE);
            }
        }
        playBuffer(buffer);
    }

    private double waveform(double phase, WaveType type) {
        double s = Math.sin(phase);
        switch (type) {
            case SINE:     return s;
            case SQUARE:   return s >= 0 ? 1.0 : -1.0;
            case TRIANGLE: return 2.0 / Math.PI * Math.asin(s);
            case SAW:      return 2.0 * (phase / (2 * Math.PI) - Math.floor(phase / (2 * Math.PI) + 0.5));
            default:       return s;
        }
    }

    /** Плавная атака ~5% и затухание ~30% */
    private double envelope(int i, int total) {
        double attack = total * 0.05;
        double release = total * 0.30;
        if (i < attack) return i / attack;
        if (i > total - release) return (total - i) / release;
        return 1.0;
    }

    /** Отправляет PCM-буфер в AudioTrack в отдельном потоке */
    private void playBuffer(short[] buffer) {
        AudioTrack track;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(buffer.length * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
            } else {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        buffer.length * 2, AudioTrack.MODE_STATIC);
            }
        } catch (Exception e) {
            return; // нет звука — не критично
        }

        track.write(buffer, 0, buffer.length);
        activeTracks.add(track);

        track.setPlaybackPositionUpdateListener(
                new AudioTrack.OnPlaybackPositionUpdateListener() {
                    @Override public void onMarkerReached(AudioTrack t) { /* unused */ }
                    @Override public void onPeriodicNotification(AudioTrack t) { /* unused */ }
                });

        track.play();
        // Освобождаем через длительность буфера + запас
        final int durationMs = buffer.length * 1000 / SAMPLE_RATE;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> releaseTrack(track), durationMs + 200);
    }

    private void releaseTrack(AudioTrack track) {
        try {
            if (track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) track.stop();
            track.release();
        } catch (Exception ignored) {}
        activeTracks.remove(track);
    }

    /** Освободить ресурсы */
    public void release() {
        for (AudioTrack t : new java.util.ArrayList<>(activeTracks)) releaseTrack(t);
        activeTracks.clear();
    }
}