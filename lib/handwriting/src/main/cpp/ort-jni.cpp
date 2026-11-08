/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */

// OrtModel's native half: one ORT-format model of one input, floats or int64s (the voice polish
// tagger's character ids), and float outputs, used where it lies: the model file mapped (a resource
// pack's, OrtModel.open), the mapping held by OrtModel as long as the session; onnxruntime reads the
// weights in place. Prepacking would copy them, and crashes on bytes used in place (lib/sherpa-onnx
// README), so it is off.

#include <jni.h>

#include <cstdint>
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

void raise(JNIEnv *jni, const char *cls, const std::string &what) {
    jclass c = jni->FindClass(cls);
    if (c) jni->ThrowNew(c, what.c_str());
}

struct Model {
    Ort::Session session;
    std::string input;
    ONNXTensorElementDataType inputType;
    std::vector<std::string> outputs;

    Model(const void *data, size_t size, const Ort::SessionOptions &opts)
            : session(env(), data, size, opts) {
        Ort::AllocatorWithDefaultOptions alloc;
        if (session.GetInputCount() != 1) throw std::invalid_argument("not one input");
        input = session.GetInputNameAllocated(0, alloc).get();
        inputType = session.GetInputTypeInfo(0).GetTensorTypeAndShapeInfo().GetElementType();
        if (inputType != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT && inputType != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
            throw std::invalid_argument("input neither floats nor int64s");
        }
        for (size_t i = 0; i < session.GetOutputCount(); i++) {
            outputs.emplace_back(session.GetOutputNameAllocated(i, alloc).get());
            // the outputs are read as floats, another type's bytes wrongly (a narrower one's past
            // its end): refused as the model opens, the recognizer then not loaded, rather than at each read
            if (session.GetOutputTypeInfo(i).GetTensorTypeAndShapeInfo().GetElementType() !=
                ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
                throw std::invalid_argument("output not of floats");
            }
        }
        if (outputs.empty()) throw std::invalid_argument("no output");
    }
};

// [jshape]'s dimensions, and how many values they hold; -1 if one is negative
std::vector<int64_t> shapeOf(JNIEnv *jni, jlongArray jshape, size_t &n) {
    std::vector<int64_t> shape(jni->GetArrayLength(jshape));
    jni->GetLongArrayRegion(jshape, 0, static_cast<jsize>(shape.size()), reinterpret_cast<jlong *>(shape.data()));
    n = 1;
    // a negative dimension, or a product past size_t, is no size an array has: -1, never matched
    for (auto d: shape) {
        auto u = static_cast<size_t>(d);
        if (d < 0 || (u != 0 && n > SIZE_MAX / u)) {
            n = static_cast<size_t>(-1);
            break;
        }
        n *= u;
    }
    return shape;
}

// output [i] of [out]: its values, its dimensions into [jdims] from [at] (-1 past them); null with an
// exception pending if they do not fit
jfloatArray outputOf(JNIEnv *jni, std::vector<Ort::Value> &out, size_t i, jlongArray jdims, jsize at, jsize rank) {
    auto info = out[i].GetTensorTypeAndShapeInfo();
    auto dims = info.GetShape();
    if (dims.size() > static_cast<size_t>(rank)) {
        raise(jni, "java/lang/IllegalStateException", "output of more dimensions than expected");
        return nullptr;
    }
    std::vector<jlong> d(rank, -1);
    for (size_t k = 0; k < dims.size(); k++) d[k] = dims[k];
    jni->SetLongArrayRegion(jdims, at, rank, d.data());
    size_t count = info.GetElementCount();
    jfloatArray result = jni->NewFloatArray(static_cast<jsize>(count));
    if (result) jni->SetFloatArrayRegion(result, 0, static_cast<jsize>(count), out[i].GetTensorData<float>());
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeOpen(
        JNIEnv *jni, jclass, jobject jbytes, jint threads) {
    // a direct buffer (a mapped file): its bytes stay where they are, OrtModel holding it till closed
    const void *data = jni->GetDirectBufferAddress(jbytes);
    jlong capacity = jni->GetDirectBufferCapacity(jbytes);
    if (!data || capacity <= 0) {
        raise(jni, "java/lang/IllegalArgumentException", "not a direct buffer with a model in it");
        return 0;
    }
    // a C++ exception crossing JNI would abort the process: every one becomes Java's
    try {
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
        return reinterpret_cast<jlong>(new Model(data, static_cast<size_t>(capacity), opts));
    } catch (const std::exception &e) {
        raise(jni, "java/lang/IllegalStateException", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeRun(
        JNIEnv *jni, jclass, jlong handle, jfloatArray jx, jlongArray jshape, jlongArray jout) {
    auto *model = reinterpret_cast<Model *>(handle);
    try {
        if (model->inputType != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
            raise(jni, "java/lang/IllegalArgumentException", "the model takes int64s, not floats");
            return nullptr;
        }
        size_t n;
        auto shape = shapeOf(jni, jshape, n);
        if (n != static_cast<size_t>(jni->GetArrayLength(jx))) {
            raise(jni, "java/lang/IllegalArgumentException", "input size does not match its shape");
            return nullptr;
        }
        std::vector<float> x(n);
        jni->GetFloatArrayRegion(jx, 0, static_cast<jsize>(n), x.data());
        auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        auto in = Ort::Value::CreateTensor<float>(mem, x.data(), n, shape.data(), shape.size());
        const char *inName = model->input.c_str();
        const char *outName = model->outputs[0].c_str();
        auto out = model->session.Run(Ort::RunOptions{nullptr}, &inName, &in, 1, &outName, 1);
        return outputOf(jni, out, 0, jout, 0, jni->GetArrayLength(jout));
    } catch (const std::exception &e) {
        raise(jni, "java/lang/IllegalStateException", e.what());
        return nullptr;
    }
}

// every output, on int64s: an array of float arrays, their dimensions [rank] each into [jdims]
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeRunLongs(
        JNIEnv *jni, jclass, jlong handle, jlongArray jx, jlongArray jshape, jlongArray jdims, jint rank) {
    auto *model = reinterpret_cast<Model *>(handle);
    try {
        if (model->inputType != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
            raise(jni, "java/lang/IllegalArgumentException", "the model takes floats, not int64s");
            return nullptr;
        }
        size_t outputs = model->outputs.size();
        if (rank <= 0 || static_cast<size_t>(jni->GetArrayLength(jdims)) != outputs * static_cast<size_t>(rank)) {
            raise(jni, "java/lang/IllegalArgumentException", "dimensions not rank for each output");
            return nullptr;
        }
        size_t n;
        auto shape = shapeOf(jni, jshape, n);
        if (n != static_cast<size_t>(jni->GetArrayLength(jx))) {
            raise(jni, "java/lang/IllegalArgumentException", "input size does not match its shape");
            return nullptr;
        }
        std::vector<int64_t> x(n);
        jni->GetLongArrayRegion(jx, 0, static_cast<jsize>(n), reinterpret_cast<jlong *>(x.data()));
        auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
        auto in = Ort::Value::CreateTensor<int64_t>(mem, x.data(), n, shape.data(), shape.size());
        const char *inName = model->input.c_str();
        std::vector<const char *> outNames;
        for (auto &o: model->outputs) outNames.push_back(o.c_str());
        auto out = model->session.Run(Ort::RunOptions{nullptr}, &inName, &in, 1, outNames.data(), outputs);
        jclass floats = jni->FindClass("[F");
        if (!floats) return nullptr;
        jobjectArray result = jni->NewObjectArray(static_cast<jsize>(outputs), floats, nullptr);
        if (!result) return nullptr;
        for (size_t i = 0; i < outputs; i++) {
            jfloatArray values = outputOf(jni, out, i, jdims, static_cast<jsize>(i) * rank, rank);
            if (!values) return nullptr;
            jni->SetObjectArrayElement(result, static_cast<jsize>(i), values);
            jni->DeleteLocalRef(values);
        }
        return result;
    } catch (const std::exception &e) {
        raise(jni, "java/lang/IllegalStateException", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeOutputs(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(reinterpret_cast<Model *>(handle)->outputs.size());
}

extern "C" JNIEXPORT void JNICALL
Java_org_fcitx_fcitx5_android_handwriting_OrtModel_nativeClose(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Model *>(handle);
}
