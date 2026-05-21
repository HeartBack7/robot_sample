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

package com.ainirobot.robotos.bailian;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 阿里云百炼 DashScope OpenAI 兼容模式 - 全模态（Qwen-Omni）流式音频问答。
 *
 * <p>历史：早期使用 Qwen-Audio（{@code qwen-audio-turbo-latest}），但官方说明该系列仅供免费体验、
 * 不支持付费，对生产应用推荐迁移至 Qwen-Omni。本类适配 Qwen-Omni 接口（默认
 * {@code qwen3-omni-flash}）。
 *
 * <p>关键差异（相对 Qwen-Audio）：
 * <ul>
 *   <li>仍走 {@code /compatible-mode/v1/chat/completions} 端点，OpenAI 兼容格式；</li>
 *   <li>messages.content 仍为数组，包含 {@code type=input_audio}（base64 WAV）与 {@code type=text}；</li>
 *   <li>Qwen-Omni 必须 {@code stream=true}，且必须显式声明 {@code modalities}；</li>
 *   <li>必须设置 {@code stream_options.include_usage=true}；</li>
 *   <li>当模型是 {@code qwen3-omni-flash} 等混合思考模型时，音频/视频输入需关闭思考模式
 *       （{@code enable_thinking=false}），否则会拒绝请求或表现异常。</li>
 * </ul>
 *
 * <p>文档：<a href="https://help.aliyun.com/zh/model-studio/qwen-omni">Qwen-Omni</a>。
 */
public final class DashScopeAudioChat {

    private static final String ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    public interface StreamListener {
        void onDelta(String text);

        void onComplete(String fullText);

        void onError(String message);
    }

    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = connection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 同步调用：请在后台线程执行。
     *
     * <p>请求体按 Qwen-Omni 规范构造：
     * <pre>
     * {
     *   "model": "qwen3-omni-flash",
     *   "stream": true,
     *   "stream_options": { "include_usage": true },
     *   "modalities": ["text"],
     *   "enable_thinking": false,
     *   "messages": [ { "role":"user", "content":[
     *       { "type":"input_audio", "input_audio":{ "data":"data:;base64,...", "format":"wav" } },
     *       { "type":"text", "text":"..." }
     *   ] } ]
     * }
     * </pre>
     *
     * @param wavBytes   完整 WAV 字节（含 44 字节 header），将被 base64 编码后内联到请求体。
     * @param userPrompt 文本提示词，用于引导模型如何回答音频内容。
     */
    public void streamChatWithAudio(String apiKey,
                                    String model,
                                    byte[] wavBytes,
                                    String userPrompt,
                                    StreamListener listener) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            listener.onError("empty_api_key");
            return;
        }
        if (wavBytes == null || wavBytes.length == 0) {
            listener.onError("empty_audio");
            return;
        }
        cancelled = false;
        HttpURLConnection conn = null;
        InputStream input = null;
        BufferedReader reader = null;
        StringBuilder full = new StringBuilder();
        try {
            // NO_WRAP 避免 base64 中出现 \n，否则会破坏后续 SSE 行解析与 JSON 转义。
            String base64Wav = Base64.encodeToString(wavBytes, Base64.NO_WRAP);
            String dataUri = "data:;base64," + base64Wav;

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("stream", true);

            // Qwen-Omni 强制要求声明输出模态；只要文本以节省成本并避免 audio chunk 解析开销。
            JSONArray modalities = new JSONArray();
            modalities.put("text");
            body.put("modalities", modalities);

            // Qwen-Omni 流式必须打开 usage 上报，否则部分版本会校验失败。
            JSONObject streamOptions = new JSONObject();
            streamOptions.put("include_usage", true);
            body.put("stream_options", streamOptions);

            // qwen3-omni-flash 是混合思考模型，音频/视频输入需在非思考模式下运行。对 qwen-omni-turbo
            // 等不支持该字段的模型来说该参数会被忽略，因此可以无条件附带以保持向前兼容。
            body.put("enable_thinking", false);

            JSONArray messages = new JSONArray();
            JSONObject user = new JSONObject();
            user.put("role", "user");

            JSONArray content = new JSONArray();

            JSONObject audioPart = new JSONObject();
            audioPart.put("type", "input_audio");
            JSONObject audioInfo = new JSONObject();
            audioInfo.put("data", dataUri);
            audioInfo.put("format", "wav");
            audioPart.put("input_audio", audioInfo);
            content.put(audioPart);

            JSONObject textPart = new JSONObject();
            textPart.put("type", "text");
            textPart.put("text", userPrompt == null || userPrompt.isEmpty()
                    ? "请用简体中文回答音频中的问题，如果没有问题就用一句话总结音频内容。"
                    : userPrompt);
            content.put(textPart);

            user.put("content", content);
            messages.put(user);
            body.put("messages", messages);

            Charset utf8 = Charset.forName("UTF-8");
            byte[] payload = body.toString().getBytes(utf8);

            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            connection = conn;
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            input = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (input == null) {
                listener.onError("http_" + code);
                return;
            }
            reader = new BufferedReader(new InputStreamReader(input, utf8));
            if (code >= 400) {
                StringBuilder err = new StringBuilder();
                String errLine;
                while ((errLine = reader.readLine()) != null) {
                    err.append(errLine).append('\n');
                }
                String errBody = err.toString().trim();
                if (errBody.length() > 0) {
                    try {
                        JSONObject o = new JSONObject(errBody);
                        JSONObject errObj = o.optJSONObject("error");
                        if (errObj != null) {
                            listener.onError(errObj.optString("message", errBody));
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                    listener.onError(errBody.length() > 240 ? errBody.substring(0, 240) + "..." : errBody);
                    return;
                }
                listener.onError("http_" + code);
                return;
            }

            // SSE：每条事件以 "data: " 开头；以 [DONE] 结束。
            // 增量正文位于 choices[0].delta.content。
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    break;
                }
                if (line.length() == 0) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JSONObject obj = new JSONObject(data);
                    JSONArray choices = obj.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject choice0 = choices.getJSONObject(0);
                    JSONObject delta = choice0.optJSONObject("delta");
                    if (delta == null) {
                        continue;
                    }
                    // content 在多模态返回中通常仍是字符串增量；少数情况下可能是数组。
                    Object contentVal = delta.opt("content");
                    String piece = extractText(contentVal);
                    if (piece != null && piece.length() > 0) {
                        full.append(piece);
                        listener.onDelta(piece);
                    }
                } catch (Exception ignored) {
                }
            }
            if (cancelled) {
                listener.onError("cancelled");
            } else {
                listener.onComplete(full.toString());
            }
        } catch (Exception e) {
            if (cancelled) {
                listener.onError("cancelled");
            } else {
                listener.onError(e.getMessage() != null ? e.getMessage() : "unknown_error");
            }
        } finally {
            connection = null;
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (input != null) {
                    input.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String extractText(Object contentVal) {
        if (contentVal == null) {
            return null;
        }
        if (contentVal instanceof String) {
            return (String) contentVal;
        }
        if (contentVal instanceof JSONArray) {
            JSONArray arr = (JSONArray) contentVal;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject part = arr.optJSONObject(i);
                if (part == null) {
                    continue;
                }
                String t = part.optString("text", "");
                if (t.length() > 0) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return contentVal.toString();
    }
}
