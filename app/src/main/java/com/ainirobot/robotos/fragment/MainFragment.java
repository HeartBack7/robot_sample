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

import android.content.Context;
import android.view.View;

import androidx.fragment.app.Fragment;

import com.ainirobot.robotos.R;

public class MainFragment extends BaseFragment {

    @Override
    public View onCreateView(Context context) {
        View root = mInflater.inflate(R.layout.fragment_main_layout, null, false);
        bindViews(root);
        hideBackView();
        hideResultView();
        return root;
    }

    private void bindViews(View root) {
        root.findViewById(R.id.btn_voice_qa_entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFragment(VoiceQaFragment.newInstance());
            }
        });

        root.findViewById(R.id.btn_audio_qa_entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFragment(AudioQaFragment.newInstance());
            }
        });

        root.findViewById(R.id.nlu_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFragment(NluTestFragment.newInstance());
            }
        });

        root.findViewById(R.id.navigation_scene).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFragment(NavigationFragment.newInstance());
            }
        });

        root.findViewById(R.id.btn_speech_entry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFragment(SpeechFragment.newInstance());
            }
        });

        root.findViewById(R.id.exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (getActivity() != null) {
                    getActivity().onBackPressed();
                    getActivity().finish();
                }
            }
        });
    }

    public static Fragment newInstance() {
        return new MainFragment();
    }
}
