/*
 *  Copyright (C) 2017 OrionStar Technology Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ainirobot.robotos.fragment;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.ainirobot.coreservice.client.listener.TextListener;
import com.ainirobot.coreservice.client.speech.SkillApi;
import com.ainirobot.coreservice.client.speech.entity.TTSEntity;
import com.ainirobot.robotos.BuildConfig;
import com.ainirobot.robotos.LogTools;
import com.ainirobot.robotos.R;
import com.ainirobot.robotos.application.RobotOSApplication;
import com.ainirobot.robotos.bailian.DashScopeAudioChat;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 百炼 Qwen-Omni 语音大模型「实时对话」入口。
 *
 * <p>交互模型：按钮控制整段会话的开/关，而不是单次录音。
 * <ul>
 *   <li>IDLE：按钮文案为「开始实时对话」。点击后进入 SESSION_ACTIVE。</li>
 *   <li>SESSION_ACTIVE：按钮文案为「结束实时对话」。后台持续采集 PCM、用 RMS 能量阈值做 VAD 自动切句，
 *       切出的每段音频通过百炼 Qwen-Omni 流式 API 拿回答，再交给机器人 TTS 播报。再次点击按钮即彻底
 *       结束会话、清理所有资源。</li>
 * </ul>
 *
 * <p>线程模型：录音线程持续读 PCM 与做 VAD；处理线程串行从队列取 utterance → 上传 → 等 TTS 播完。
 * TTS 播报期间采取半双工策略，麦克风线程读到的样本被丢弃，避免回采机器人自己说话造成自激与死循环。
 */
public class AudioQaFragment extends BaseFragment {

    private static final int REQ_RECORD_AUDIO = 0x7002;

    /** Qwen-Omni 在 OpenAI 兼容模式下要求 16kHz / 单声道 / 16bit PCM-WAV。 */
    private static final int SAMPLE_RATE_HZ = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;

    /** 单段 utterance 的硬上限：超过即强制截断提交，避免无停顿长讲堆爆内存。 */
    private static final int MAX_UTTERANCE_SECONDS = 20;

    /** VAD 帧长（毫秒）。20ms / 16kHz / 16bit / mono = 320 样本 = 640 字节。 */
    private static final int VAD_FRAME_MS = 20;
    private static final int VAD_FRAME_SAMPLES = SAMPLE_RATE_HZ * VAD_FRAME_MS / 1000;
    private static final int VAD_FRAME_BYTES = VAD_FRAME_SAMPLES * 2;

    /** 噪声底校准时长：进入会话后用前 ~500ms PCM 估计环境 RMS，再据此计算阈值。 */
    private static final int NOISE_CAL_MS = 500;
    private static final int NOISE_CAL_FRAMES = NOISE_CAL_MS / VAD_FRAME_MS;

    /** 语音起点：连续 ~250ms 帧能量高于阈值。 */
    private static final int SPEECH_ONSET_MS = 250;
    private static final int SPEECH_ONSET_FRAMES = SPEECH_ONSET_MS / VAD_FRAME_MS;

    /** 语音终点：连续 ~800ms 帧能量低于阈值（即句末静默）。 */
    private static final int SPEECH_OFFSET_MS = 800;
    private static final int SPEECH_OFFSET_FRAMES = SPEECH_OFFSET_MS / VAD_FRAME_MS;

    /** 句首预滚音频：把检测到 onset 之前最近 ~300ms PCM 也并入 utterance，避免砍掉起手字头。 */
    private static final int PRE_ROLL_MS = 300;
    private static final int PRE_ROLL_FRAMES = PRE_ROLL_MS / VAD_FRAME_MS;

    /** 阈值下限：极安静环境下也要求 RMS 至少超过这个值才认作 speech，避免噪声底为 0 时把任何风扇声当 speech。 */
    private static final double STATIC_NOISE_FLOOR = 400.0;
    /** 动态阈值 = max(NOISE_MULTIPLIER * 估计噪声底, STATIC_NOISE_FLOOR)。 */
    private static final double NOISE_MULTIPLIER = 2.5;

    /** 太短的段不发请求，避免咳嗽/键盘声触发模型；单位字节，16-bit PCM 0.5s = 16000 字节。 */
    private static final int MIN_SUBMIT_BYTES = SAMPLE_RATE_HZ;

    /** 外层会话状态：按钮文本只跟这一层对应。 */
    private enum SessionState { IDLE, SESSION_ACTIVE }

    /** 内层子状态：仅在 SESSION_ACTIVE 下有意义，影响日志与麦克风暂停标志。 */
    private enum SubState { LISTENING, SEGMENT_UPLOADING, TTS_PLAYING }

    private TextView tvAudioQa;
    private ScrollView svAudioQa;
    private Button btnAudioQa;
    private SkillApi mSkillApi;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor();
    private final ExecutorService recordPool = Executors.newSingleThreadExecutor();
    private final DashScopeAudioChat dashClient = new DashScopeAudioChat();

    /** 串行排队：录音线程产，处理线程消。每个元素是单段 utterance 的 PCM 字节。 */
    private final LinkedBlockingQueue<byte[]> utteranceQueue = new LinkedBlockingQueue<>();

    private volatile SessionState sessionState = SessionState.IDLE;
    private volatile SubState subState = SubState.LISTENING;
    private volatile boolean sessionActive;
    /** TTS 播报期间录音线程读到的 PCM 直接丢弃，半双工避免自激。 */
    private volatile boolean micPaused;
    private volatile AudioRecord audioRecord;

    @Override
    public View onCreateView(Context context) {
        mSkillApi = RobotOSApplication.getInstance().getSkillApi();
        View root = mInflater.inflate(R.layout.fragment_audio_qa_layout, null, false);
        bindViews(root);
        showBackView();
        hideResultView();
        return root;
    }

    @Override
    public void onDestroyView() {
        stopSession(false);
        ioPool.shutdownNow();
        recordPool.shutdownNow();
        super.onDestroyView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSession();
            } else {
                Toast.makeText(mActivity, R.string.audio_qa_mic_denied, Toast.LENGTH_LONG).show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void bindViews(View root) {
        svAudioQa = root.findViewById(R.id.sv_audio_qa);
        tvAudioQa = root.findViewById(R.id.tv_audio_qa);
        btnAudioQa = root.findViewById(R.id.btn_audio_qa);
        btnAudioQa.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAudioQaClicked();
            }
        });
        updateButton();
    }

    private void onAudioQaClicked() {
        if (sessionState == SessionState.SESSION_ACTIVE) {
            stopSession(true);
            return;
        }
        if (BuildConfig.DASHSCOPE_API_KEY == null || BuildConfig.DASHSCOPE_API_KEY.trim().isEmpty()) {
            Toast.makeText(mActivity, R.string.audio_qa_no_key, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(mActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
                return;
            }
        }
        startSession();
    }

    /** 开启实时对话会话：拉起 AudioRecord、录音线程与处理线程；按钮变为「结束实时对话」。 */
    private void startSession() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuf <= 0) {
            Toast.makeText(mActivity, R.string.audio_qa_record_error, Toast.LENGTH_LONG).show();
            return;
        }
        final int bufferSize = Math.max(minBuf * 4, SAMPLE_RATE_HZ * 2);
        AudioRecord ar;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
        } catch (Exception e) {
            ar = null;
        }
        if (ar == null || ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(mActivity, R.string.audio_qa_record_error, Toast.LENGTH_LONG).show();
            if (ar != null) {
                try { ar.release(); } catch (Exception ignored) { }
            }
            return;
        }
        try {
            ar.startRecording();
        } catch (Exception e) {
            try { ar.release(); } catch (Exception ignored) { }
            Toast.makeText(mActivity, R.string.audio_qa_record_error, Toast.LENGTH_LONG).show();
            return;
        }
        audioRecord = ar;
        utteranceQueue.clear();
        sessionActive = true;
        micPaused = false;
        sessionState = SessionState.SESSION_ACTIVE;
        subState = SubState.LISTENING;
        appendLine(getString(R.string.audio_qa_session_started));
        updateButton();
        LogTools.info("AudioQa session start");
        recordPool.execute(recordLoop);
        ioPool.execute(processLoop);
    }

    /**
     * 结束实时对话会话：停录音、取消飞行中的请求、停 TTS、清队列；按钮回到「开始实时对话」。
     *
     * @param submitLog true 时在 UI 输出"实时对话已结束"提示；生命周期销毁路径走 false。
     */
    private void stopSession(boolean submitLog) {
        if (!sessionActive && sessionState == SessionState.IDLE && audioRecord == null) {
            return;
        }
        sessionActive = false;
        micPaused = false;
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                ar.release();
            } catch (Exception ignored) {
            }
        }
        dashClient.cancel();
        stopRobotTts();
        utteranceQueue.clear();
        sessionState = SessionState.IDLE;
        subState = SubState.LISTENING;
        if (submitLog) {
            appendLine(getString(R.string.audio_qa_stopped));
        }
        updateButton();
        LogTools.info("AudioQa session stop");
    }

    /**
     * 持续录音 + VAD 切分主循环。状态机：silent ↔ speaking；预滚 PRE_ROLL_FRAMES 帧
     * 被加在 onset 之后的 utterance 前面以保住起手字头。
     */
    private final Runnable recordLoop = new Runnable() {
        @Override
        public void run() {
            final byte[] frame = new byte[VAD_FRAME_BYTES];
            final byte[][] preRoll = new byte[Math.max(1, PRE_ROLL_FRAMES)][];
            int preRollWrite = 0;
            int preRollCount = 0;

            final double[] noiseSamples = new double[NOISE_CAL_FRAMES];
            int noiseCollected = 0;
            double threshold = STATIC_NOISE_FLOOR;

            boolean speaking = false;
            int voicedRun = 0;
            int silentRun = 0;
            final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
            final int utteranceBytesLimit = SAMPLE_RATE_HZ * 2 * CHANNELS * MAX_UTTERANCE_SECONDS;

            int filled = 0;
            try {
                while (sessionActive) {
                    AudioRecord ar = audioRecord;
                    if (ar == null) {
                        break;
                    }
                    int n;
                    try {
                        n = ar.read(frame, filled, VAD_FRAME_BYTES - filled);
                    } catch (Exception e) {
                        break;
                    }
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    filled += n;
                    if (filled < VAD_FRAME_BYTES) {
                        continue;
                    }
                    filled = 0;

                    if (micPaused) {
                        // TTS 播报期间：丢帧并复位 VAD 状态，避免把残尾当成下一句的起点。
                        speaking = false;
                        voicedRun = 0;
                        silentRun = 0;
                        utterance.reset();
                        preRollCount = 0;
                        preRollWrite = 0;
                        continue;
                    }

                    double rms = computeRms(frame, VAD_FRAME_BYTES);

                    if (noiseCollected < NOISE_CAL_FRAMES) {
                        noiseSamples[noiseCollected++] = rms;
                        if (noiseCollected == NOISE_CAL_FRAMES) {
                            double med = median(noiseSamples);
                            threshold = Math.max(med * NOISE_MULTIPLIER, STATIC_NOISE_FLOOR);
                            LogTools.info("AudioQa VAD calibrated noise=" + (int) med
                                    + " threshold=" + (int) threshold);
                        }
                        preRoll[preRollWrite] = frame.clone();
                        preRollWrite = (preRollWrite + 1) % preRoll.length;
                        if (preRollCount < preRoll.length) {
                            preRollCount++;
                        }
                        continue;
                    }

                    boolean voiced = rms > threshold;

                    if (!speaking) {
                        preRoll[preRollWrite] = frame.clone();
                        preRollWrite = (preRollWrite + 1) % preRoll.length;
                        if (preRollCount < preRoll.length) {
                            preRollCount++;
                        }
                        voicedRun = voiced ? voicedRun + 1 : 0;
                        if (voicedRun >= SPEECH_ONSET_FRAMES) {
                            speaking = true;
                            int start = (preRollWrite - preRollCount + preRoll.length) % preRoll.length;
                            for (int i = 0; i < preRollCount; i++) {
                                byte[] f = preRoll[(start + i) % preRoll.length];
                                if (f != null) {
                                    utterance.write(f, 0, f.length);
                                }
                            }
                            voicedRun = 0;
                            silentRun = 0;
                            LogTools.info("AudioQa VAD speech onset rms=" + (int) rms);
                        }
                    } else {
                        utterance.write(frame, 0, frame.length);
                        silentRun = voiced ? 0 : silentRun + 1;
                        boolean endSilent = silentRun >= SPEECH_OFFSET_FRAMES;
                        boolean tooLong = utterance.size() >= utteranceBytesLimit;
                        if (endSilent || tooLong) {
                            byte[] pcm = utterance.toByteArray();
                            utterance.reset();
                            speaking = false;
                            voicedRun = 0;
                            silentRun = 0;
                            preRollCount = 0;
                            preRollWrite = 0;
                            if (pcm.length >= MIN_SUBMIT_BYTES) {
                                LogTools.info("AudioQa VAD segment offset bytes=" + pcm.length
                                        + (tooLong ? " (max-cut)" : ""));
                                utteranceQueue.offer(pcm);
                                if (tooLong) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (sessionActive && mActivity != null) {
                                                Toast.makeText(mActivity,
                                                        R.string.audio_qa_max_duration_reached,
                                                        Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    });
                                }
                            } else {
                                LogTools.info("AudioQa VAD segment too short, drop bytes=" + pcm.length);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LogTools.info("AudioQa record loop error: " + e.getMessage());
            }
            LogTools.info("AudioQa record loop exit");
        }
    };

    /**
     * 处理线程：从队列串行取 utterance → 上传 Qwen-Omni → TTS 播报；与录音线程并行运转。
     * 当某段 utterance 上传/TTS 期间又切出了新的一段，新的暂存在 {@link #utteranceQueue} 里，
     * 当前段处理完后再取一个出来；会话结束时整个队列被清空，已飞行的请求 cancel。
     */
    private final Runnable processLoop = new Runnable() {
        @Override
        public void run() {
            while (sessionActive) {
                final byte[] pcm;
                try {
                    pcm = utteranceQueue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (pcm == null) {
                    continue;
                }
                if (!sessionActive) {
                    return;
                }
                final byte[] wav = buildWav(pcm, SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE);
                LogTools.info("AudioQa segment upload wav bytes=" + wav.length);
                subState = SubState.SEGMENT_UPLOADING;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        appendLine("您：[语音]");
                        appendLine("百炼：");
                    }
                });

                final CountDownLatch streamDone = new CountDownLatch(1);
                final StringBuilder fullHolder = new StringBuilder();
                final boolean[] errFlag = new boolean[]{false};
                final String[] errMsgHolder = new String[]{null};
                dashClient.streamChatWithAudio(
                        BuildConfig.DASHSCOPE_API_KEY,
                        BuildConfig.DASHSCOPE_AUDIO_MODEL,
                        wav,
                        "请用简体中文回答音频中的问题；若不是问题，请用一句话总结音频内容。",
                        new DashScopeAudioChat.StreamListener() {
                            @Override
                            public void onDelta(final String text) {
                                fullHolder.append(text);
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        appendToLastAssistantLine(text);
                                    }
                                });
                            }

                            @Override
                            public void onComplete(String fullText) {
                                streamDone.countDown();
                            }

                            @Override
                            public void onError(String message) {
                                errFlag[0] = true;
                                errMsgHolder[0] = message;
                                streamDone.countDown();
                            }
                        });
                try {
                    streamDone.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!sessionActive) {
                    return;
                }
                if (errFlag[0]) {
                    final String msg = errMsgHolder[0];
                    LogTools.info("AudioQa segment upload error: " + msg);
                    if (!"cancelled".equals(msg)) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                appendLine(getString(R.string.audio_qa_network_error)
                                        + ": " + (msg == null ? "" : msg));
                            }
                        });
                    }
                    subState = SubState.LISTENING;
                    continue;
                }
                final String fullText = fullHolder.toString();
                if (TextUtils.isEmpty(fullText.trim())) {
                    subState = SubState.LISTENING;
                    continue;
                }

                // TTS 播报阶段：半双工——暂停麦克风采集，避免回采机器人自己说话造成自激。
                final CountDownLatch ttsDone = new CountDownLatch(1);
                subState = SubState.TTS_PLAYING;
                micPaused = true;
                LogTools.info("AudioQa TTS speaking, mic paused");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        speakAnswer(fullText, ttsDone);
                    }
                });
                try {
                    // 兜底超时：某些 TTS 异常情况下可能不回调，避免处理线程永久卡住。
                    if (!ttsDone.await(60, TimeUnit.SECONDS)) {
                        LogTools.info("AudioQa TTS latch timeout, force resume");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    micPaused = false;
                    return;
                }
                micPaused = false;
                subState = SubState.LISTENING;
                LogTools.info("AudioQa TTS done, mic resumed");
            }
        }
    };

    /** 为一段 utterance 构造一次性 TTS 监听器：onComplete/onStop/onError 都释放 latch。 */
    private TextListener makeTtsListener(final CountDownLatch latch) {
        return new TextListener() {
            @Override
            public void onStart() {
                super.onStart();
                LogTools.info("AudioQa TTS onStart");
            }

            @Override
            public void onStop() {
                super.onStop();
                LogTools.info("AudioQa TTS onStop");
                latch.countDown();
            }

            @Override
            public void onComplete() {
                super.onComplete();
                LogTools.info("AudioQa TTS onComplete");
                latch.countDown();
            }

            @Override
            public void onError() {
                super.onError();
                LogTools.info("AudioQa TTS onError");
                latch.countDown();
            }
        };
    }

    private void speakAnswer(String fullText, CountDownLatch latch) {
        if (mSkillApi == null) {
            mSkillApi = RobotOSApplication.getInstance().getSkillApi();
        }
        if (mSkillApi == null) {
            latch.countDown();
            return;
        }
        String toSpeak = sanitizeForTts(fullText);
        if (TextUtils.isEmpty(toSpeak)) {
            latch.countDown();
            return;
        }
        try {
            mSkillApi.playText(new TTSEntity("audio-qa-" + System.currentTimeMillis(), toSpeak),
                    makeTtsListener(latch));
        } catch (Exception e) {
            LogTools.info("AudioQa playText error: " + e.getMessage());
            latch.countDown();
        }
    }

    private void stopRobotTts() {
        if (mSkillApi != null) {
            try {
                mSkillApi.stopTTS();
            } catch (Exception ignored) {
            }
        }
    }

    private void updateButton() {
        if (btnAudioQa == null) {
            return;
        }
        if (sessionState == SessionState.SESSION_ACTIVE) {
            btnAudioQa.setText(R.string.audio_qa_stop);
        } else {
            btnAudioQa.setText(R.string.audio_qa_start);
        }
        btnAudioQa.setEnabled(true);
    }

    private void appendLine(String line) {
        if (tvAudioQa == null) {
            return;
        }
        CharSequence cur = tvAudioQa.getText();
        if (cur == null || cur.length() == 0
                || getString(R.string.audio_qa_hint).contentEquals(cur)) {
            tvAudioQa.setText(line);
        } else {
            tvAudioQa.append("\n");
            tvAudioQa.append(line);
        }
        scrollToBottom();
    }

    private void appendToLastAssistantLine(String delta) {
        if (tvAudioQa != null) {
            tvAudioQa.append(delta);
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        if (svAudioQa == null) {
            return;
        }
        svAudioQa.post(new Runnable() {
            @Override
            public void run() {
                svAudioQa.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String sanitizeForTts(String fullText) {
        if (TextUtils.isEmpty(fullText)) {
            return "";
        }
        String text = fullText;
        String[] uiPrompts = {
                getString(R.string.audio_qa_hint),
                getString(R.string.audio_qa_recording),
                getString(R.string.audio_qa_uploading),
                getString(R.string.audio_qa_stopped),
                getString(R.string.audio_qa_wait_answer),
                getString(R.string.audio_qa_network_error),
                getString(R.string.audio_qa_mic_denied),
                getString(R.string.audio_qa_no_key),
                getString(R.string.audio_qa_no_audio),
                getString(R.string.audio_qa_max_duration_reached),
                getString(R.string.audio_qa_record_error),
                getString(R.string.audio_qa_session_started),
                getString(R.string.audio_qa_listening),
                getString(R.string.audio_qa_segment_uploading),
                getString(R.string.audio_qa_tts_speaking)
        };
        for (String p : uiPrompts) {
            if (!TextUtils.isEmpty(p)) {
                text = text.replace(p, "");
            }
        }
        return text.trim();
    }

    /** 16-bit little-endian PCM 帧的 RMS（root-mean-square）能量。 */
    private static double computeRms(byte[] frame, int bytes) {
        long sumSq = 0;
        int n = bytes >> 1;
        for (int i = 0; i < n; i++) {
            int lo = frame[i * 2] & 0xff;
            int hi = frame[i * 2 + 1]; // signed high byte
            int s = (hi << 8) | lo;
            sumSq += (long) s * (long) s;
        }
        if (n == 0) {
            return 0.0;
        }
        return Math.sqrt((double) sumSq / (double) n);
    }

    /** 小数组中位数：拷贝排序后取中位，无副作用。 */
    private static double median(double[] data) {
        double[] cp = data.clone();
        Arrays.sort(cp);
        return cp[cp.length / 2];
    }

    /**
     * 在内存中拼接 RIFF/WAVE 头 + PCM 数据，得到完整 WAV 字节。所有 multi-byte 字段均为
     * little-endian。
     * 头部布局（共 44 字节）：
     *   0-3   "RIFF"
     *   4-7   chunkSize        = 36 + pcmLen
     *   8-11  "WAVE"
     *  12-15  "fmt "
     *  16-19  subchunk1Size    = 16 (PCM)
     *  20-21  audioFormat      = 1  (PCM)
     *  22-23  numChannels
     *  24-27  sampleRate
     *  28-31  byteRate         = sampleRate * channels * bitsPerSample/8
     *  32-33  blockAlign       = channels * bitsPerSample/8
     *  34-35  bitsPerSample
     *  36-39  "data"
     *  40-43  subchunk2Size    = pcmLen
     */
    private static byte[] buildWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int pcmLen = pcm.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int totalDataLen = pcmLen + 36;

        byte[] out = new byte[44 + pcmLen];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeIntLE(out, 4, totalDataLen);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeIntLE(out, 16, 16);
        writeShortLE(out, 20, (short) 1);
        writeShortLE(out, 22, (short) channels);
        writeIntLE(out, 24, sampleRate);
        writeIntLE(out, 28, byteRate);
        writeShortLE(out, 32, (short) blockAlign);
        writeShortLE(out, 34, (short) bitsPerSample);
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeIntLE(out, 40, pcmLen);
        System.arraycopy(pcm, 0, out, 44, pcmLen);
        return out;
    }

    private static void writeIntLE(byte[] out, int offset, int value) {
        out[offset]     = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
        out[offset + 2] = (byte) ((value >> 16) & 0xff);
        out[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShortLE(byte[] out, int offset, short value) {
        out[offset]     = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    public static Fragment newInstance() {
        return new AudioQaFragment();
    }
}
