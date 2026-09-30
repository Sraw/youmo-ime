/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_JNI_UTILS_H
#define FCITX5_ANDROID_JNI_UTILS_H

#include <jni.h>

#include <string>

void throwJavaException(JNIEnv *env, const char *msg) {
    jclass c = env->FindClass("java/lang/Exception");
    env->ThrowNew(c, msg);
    env->DeleteLocalRef(c);
}

class CString {
private:
    JNIEnv *env_;
    jstring str_;
    const char *chr_;

public:
    CString(JNIEnv *env, jstring str)
            : env_(env), str_(str), chr_(env->GetStringUTFChars(str, nullptr)) {}

    ~CString() {
        env_->ReleaseStringUTFChars(str_, chr_);
    }

    operator std::string() { return chr_; }

    operator const char *() { return chr_; }

    const char *operator*() { return chr_; }
};

template<typename T = jobject>
class JRef {
private:
    JNIEnv *env_;
    T ref_;

public:
    JRef(JNIEnv *env, jobject ref) : env_(env), ref_(reinterpret_cast<T>(ref)) {}

    ~JRef() {
        env_->DeleteLocalRef(ref_);
    }

    operator T() { return ref_; }

    T operator*() { return ref_; }
};

class JString {
private:
    JNIEnv *env_;
    jstring jstring_;

public:
    JString(JNIEnv *env, const char *chars)
            : env_(env), jstring_(env->NewStringUTF(chars)) {}

    JString(JNIEnv *env, const std::string &string)
            : JString(env, string.c_str()) {}

    ~JString() {
        env_->DeleteLocalRef(jstring_);
    }

    operator jstring() { return jstring_; }

    jstring operator*() { return jstring_; }
};

class JEnv {
private:
    JNIEnv *env = nullptr;

public:
    explicit JEnv(JavaVM *jvm) {
        if (jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
            jvm->AttachCurrentThread(&env, nullptr);
        }
    }

    operator JNIEnv *() { return env; }

    JNIEnv *operator->() { return env; }
};

class GlobalRefSingleton {
public:
    JavaVM *jvm;

    jclass Object;

    jclass String;

    jclass Integer;
    jmethodID IntegerInit;

    jclass Boolean;
    jmethodID BooleanInit;

    jclass Fcitx;
    jmethodID ShowToast;
    jmethodID HandleFcitxEvent;

    jclass InputMethodEntry;
    jmethodID InputMethodEntryInit;
    jmethodID InputMethodEntryInitWithSubMode;

    jclass RawConfig;
    jfieldID RawConfigName;
    jfieldID RawConfigValue;
    jfieldID RawConfigSubItems;
    jmethodID RawConfigInit;
    jmethodID RawConfigSetSubItems;

    jclass AddonInfo;
    jmethodID AddonInfoInit;

    jclass Action;
    jmethodID ActionInit;

    jclass Key;
    jmethodID KeyInit;

    jclass FormattedText;
    jmethodID FormattedTextFromByteCursor;

    jclass CandidateAction;
    jmethodID CandidateActionInit;

    jclass Candidate;
    jmethodID CandidateInit;

    jclass EngineBridge;
    jmethodID EngineBridgeOnEvent;
    jmethodID EngineBridgeCandidates;
    jmethodID EngineBridgeOffers;
    jmethodID EngineBridgeConfigure;
    jfieldID EngineResultHandled;
    jfieldID EngineResultCommit;
    jfieldID EngineResultPreedit;
    jfieldID EngineResultCandidates;
    jfieldID EngineResultHints;
    jfieldID EngineResultFirst;
    jfieldID EngineResultShown;
    jfieldID EngineResultTotal;
    jfieldID EngineResultActionable;
    jfieldID EngineResultLabels;
    jfieldID EngineResultRefines;

    explicit GlobalRefSingleton(JavaVM *jvm_) : jvm(jvm_) {
        JNIEnv *env;
        jvm->AttachCurrentThread(&env, nullptr);

        Object = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Object")));

        String = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/String")));

        Integer = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Integer")));
        IntegerInit = env->GetMethodID(Integer, "<init>", "(I)V");

        Boolean = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
        BooleanInit = env->GetMethodID(Boolean, "<init>", "(Z)V");

        Fcitx = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Fcitx")));
        ShowToast = env->GetStaticMethodID(Fcitx, "showToast", "(Ljava/lang/String;)V");
        HandleFcitxEvent = env->GetStaticMethodID(Fcitx, "handleFcitxEvent", "(I[Ljava/lang/Object;)V");

        InputMethodEntry = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/InputMethodEntry")));
        InputMethodEntryInit = env->GetMethodID(InputMethodEntry, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");
        InputMethodEntryInitWithSubMode = env->GetMethodID(InputMethodEntry, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

        RawConfig = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/RawConfig")));
        RawConfigName = env->GetFieldID(RawConfig, "name", "Ljava/lang/String;");
        RawConfigValue = env->GetFieldID(RawConfig, "value", "Ljava/lang/String;");
        RawConfigSubItems = env->GetFieldID(RawConfig, "subItems", "[Lorg/fcitx/fcitx5/android/core/RawConfig;");
        RawConfigInit = env->GetMethodID(RawConfig, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lorg/fcitx/fcitx5/android/core/RawConfig;)V");
        RawConfigSetSubItems = env->GetMethodID(RawConfig, "setSubItems", "([Lorg/fcitx/fcitx5/android/core/RawConfig;)V");

        AddonInfo = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/AddonInfo")));
        AddonInfoInit = env->GetMethodID(AddonInfo, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IZZZZ[Ljava/lang/String;[Ljava/lang/String;)V");

        Action = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Action")));
        ActionInit = env->GetMethodID(Action, "<init>", "(IZZZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Lorg/fcitx/fcitx5/android/core/Action;)V");

        Key = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/Key")));
        KeyInit = env->GetMethodID(Key, "<init>", "(IILjava/lang/String;Ljava/lang/String;)V");

        FormattedText = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/FormattedText")));
        FormattedTextFromByteCursor = env->GetStaticMethodID(FormattedText, "fromByteCursor", "([Ljava/lang/String;[II)Lorg/fcitx/fcitx5/android/core/FormattedText;");

        CandidateAction = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/CandidateAction")));
        CandidateActionInit = env->GetMethodID(CandidateAction, "<init>", "(ILjava/lang/String;ZLjava/lang/String;ZZ)V");

        Candidate = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/CandidateWord")));
        CandidateInit = env->GetMethodID(Candidate, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");

        EngineBridge = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/fcitx/fcitx5/android/core/EngineBridge")));
        EngineBridgeOnEvent = env->GetStaticMethodID(EngineBridge, "onEvent", "(Ljava/lang/String;IIZ)Lorg/fcitx/fcitx5/android/core/EngineBridge$Result;");
        EngineBridgeCandidates = env->GetStaticMethodID(EngineBridge, "candidates", "(Ljava/lang/String;II)[Ljava/lang/String;");
        EngineBridgeOffers = env->GetStaticMethodID(EngineBridge, "offers", "(Ljava/lang/String;I)I");
        EngineBridgeConfigure = env->GetStaticMethodID(EngineBridge, "configure", "(Ljava/lang/String;)V");
        jclass engineResult = env->FindClass("org/fcitx/fcitx5/android/core/EngineBridge$Result");
        EngineResultHandled = env->GetFieldID(engineResult, "handled", "Z");
        EngineResultCommit = env->GetFieldID(engineResult, "commit", "Ljava/lang/String;");
        EngineResultPreedit = env->GetFieldID(engineResult, "preedit", "Ljava/lang/String;");
        EngineResultCandidates = env->GetFieldID(engineResult, "candidates", "[Ljava/lang/String;");
        EngineResultHints = env->GetFieldID(engineResult, "hints", "[Ljava/lang/String;");
        EngineResultFirst = env->GetFieldID(engineResult, "first", "I");
        EngineResultShown = env->GetFieldID(engineResult, "shown", "I");
        EngineResultTotal = env->GetFieldID(engineResult, "total", "I");
        EngineResultActionable = env->GetFieldID(engineResult, "actionable", "Z");
        EngineResultLabels = env->GetFieldID(engineResult, "labels", "Ljava/lang/String;");
        EngineResultRefines = env->GetFieldID(engineResult, "refines", "Z");
        env->DeleteLocalRef(engineResult);
    }

    [[nodiscard]] JEnv AttachEnv() const { return JEnv(jvm); }
};

extern GlobalRefSingleton *GlobalRef;

#endif //FCITX5_ANDROID_JNI_UTILS_H
