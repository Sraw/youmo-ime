/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// OrtModel's native half: one ORT-format model of one float input and one float output, used
// where it lies in the APK. The asset stays open, mapped (stored uncompressed), as long as the
// session; onnxruntime reads the weights in place. Prepacking would copy them, and crashes on bytes
// used in place (lib/sherpa-onnx README), so it is off.

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include <onnxruntime_cxx_api.h>

namespace {

Ort::Env &env() {
    static Ort::Env e(ORT_LOGGING_LEVEL_WARNING, "handwriting");
    return e;
}

using Asset = std::unique_ptr<AAsset, decltype(&AAsset_close)>;

struct Model {
    // declared before the session, so closed after it
    Asset asset;
    Ort::Session session;
    std::string input;
    std::string output;

    Model(Asset &&a, const void *data, size_t size, const Ort::SessionOptions &opts)
            : asset(std::move(a)), session(env(), data, size, opts) {
        Ort::AllocatorWithDefaultOptions alloc;
        input = session.GetInputNameAllocated(0, alloc).get();
        output = session.GetOutputNameAllocated(0, alloc).get();
        // nativeRun reads the output as floats, another type's bytes wrongly (a narrower one's past
        // its end): refused as the model opens, the recognizer then not loaded, rather than at each read
        if (session.GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo().GetElementType() !=
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
            throw std::invalid_argument("output not of floats");
        }
    }
};

void raise(JNIEnv *jni, const char *cls, const std::string &what) {
    jclass c = jni->FindClass(cls);
    if (c) jni->ThrowNew(c, what.c_str());
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeOpen(
        JNIEnv *jni, jclass, jobject jassets, jstring jpath, jint threads) {
    AAssetManager *assets = AAssetManager_fromJava(jni, jassets);
    const char *path = jni->GetStringUTFChars(jpath, nullptr);
    // its OutOfMemoryError pending
    if (!path) return 0;
    // a C++ exception crossing JNI would abort the process: every one becomes Java's
    try {
        std::string name(path);
        jni->ReleaseStringUTFChars(jpath, path);
        path = nullptr;
        Asset asset(assets ? AAssetManager_open(assets, name.c_str(), AASSET_MODE_BUFFER) : nullptr, AAsset_close);
        const void *data = asset ? AAsset_getBuffer(asset.get()) : nullptr;
        if (!data) {
            raise(jni, "java/io/FileNotFoundException", "cannot map asset " + name);
            return 0;
        }
        Ort::SessionOptions opts;
        opts.SetIntraOpNumThreads(threads);
        opts.SetInterOpNumThreads(1);
        // a read a stroke: threads left spinning between them would only burn the battery
        opts.AddConfigEntry("session.intra_op.allow_spinning", "0");
        // the arena would keep the most a read ever needed (the longest pen path's activations) for
        // good, on the native heap
        opts.DisableCpuMemArena();
        opts.AddConfigEntry("session.use_ort_model_bytes_directly", "1");
        opts.AddConfigEntry("session.use_ort_model_bytes_for_initializers", "1");
        opts.AddConfigEntry("session.disable_prepacking", "1");
        size_t size = AAsset_getLength(asset.get());
        // closed with the Model, or with whichever of them is left on a throw
        return reinterpret_cast<jlong>(new Model(std::move(asset), data, size, opts));
    } catch (const std::exception &e) {
        if (path) jni->ReleaseStringUTFChars(jpath, path);
        raise(jni, "java/lang/IllegalStateException", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeRun(
        JNIEnv *jni, jclass, jlong handle, jfloatArray jx, jlongArray jshape, jlongArray jout) {
    auto *model = reinterpret_cast<Model *>(handle);
    try {
        std::vector<int64_t> shape(jni->GetArrayLength(jshape));
        jni->GetLongArrayRegion(jshape, 0, static_cast<jsize>(shape.size()), reinterpret_cast<jlong *>(shape.data()));
        size_t n = 1;
        for (auto d: shape) n *= static_cast<size_t>(d);
        if (n != static_cast<size_t>(jni->GetArrayLength(jx))) {
            raise(jni, "java/lang/IllegalArgumentException", "input size does not match its shape");
            return nullptr;
        }
        std::vector<float> x(n);
        jni->GetFloatArrayRegion(jx, 0, static_cast<jsize>(n), x.data());
        auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        auto in = Ort::Value::CreateTensor<float>(mem, x.data(), n, shape.data(), shape.size());
        const char *inName = model->input.c_str();
        const char *outName = model->output.c_str();
        auto out = model->session.Run(Ort::RunOptions{nullptr}, &inName, &in, 1, &outName, 1);
        auto info = out[0].GetTensorTypeAndShapeInfo();
        auto dims = info.GetShape();
        if (dims.size() > static_cast<size_t>(jni->GetArrayLength(jout))) {
            raise(jni, "java/lang/IllegalStateException", "output of more dimensions than expected");
            return nullptr;
        }
        std::vector<jlong> d(jni->GetArrayLength(jout), -1);
        for (size_t i = 0; i < dims.size(); i++) d[i] = dims[i];
        jni->SetLongArrayRegion(jout, 0, static_cast<jsize>(d.size()), d.data());
        size_t count = info.GetElementCount();
        jfloatArray result = jni->NewFloatArray(static_cast<jsize>(count));
        if (result) jni->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), out[0].GetTensorData<float>());
        return result;
    } catch (const std::exception &e) {
        raise(jni, "java/lang/IllegalStateException", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeClose(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Model *>(handle);
}
