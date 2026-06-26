#include <jni.h>

#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>

#include "zvec/c_api.h"

namespace {

constexpr const char *kLogTag = "PocketMindZvec";
constexpr const char *kStoreClass =
    "com/bytedance/zgx/pocketmind/storage/ZvecNativeStore";
constexpr const char *kRecordClass =
    "com/bytedance/zgx/pocketmind/storage/ZvecNativeStore$Record";
constexpr const char *kHitClass =
    "com/bytedance/zgx/pocketmind/storage/ZvecNativeStore$Hit";
constexpr int kOk = static_cast<int>(ZVEC_OK);

std::atomic<int> g_last_error_code{kOk};

int SetLast(zvec_error_code_t code) {
  const int value = static_cast<int>(code);
  g_last_error_code.store(value);
  return value;
}

bool IsOk(zvec_error_code_t code) { return SetLast(code) == kOk; }

zvec_collection_t *Collection(jlong handle) {
  return reinterpret_cast<zvec_collection_t *>(handle);
}

jlong Handle(zvec_collection_t *collection) {
  return reinterpret_cast<jlong>(collection);
}

class ScopedUtfChars {
 public:
  ScopedUtfChars(JNIEnv *env, jstring value) : env_(env), value_(value) {
    if (value_ != nullptr) {
      chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
  }

  ~ScopedUtfChars() {
    if (chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  const char *get() const { return chars_; }
  bool ok() const { return chars_ != nullptr; }

 private:
  JNIEnv *env_;
  jstring value_;
  const char *chars_ = nullptr;
};

void LogZvecError(const char *context, zvec_error_code_t code) {
  if (code == ZVEC_OK) return;
  char *message = nullptr;
  zvec_get_last_error(&message);
  __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s failed: %d %s", context,
                      static_cast<int>(code), message ? message : "");
  if (message != nullptr) {
    zvec_free(message);
  }
}

bool EnsureInitialized() {
  if (zvec_is_initialized()) {
    SetLast(ZVEC_OK);
    return true;
  }

  const zvec_error_code_t code = zvec_initialize(nullptr);
  if (code == ZVEC_OK || code == ZVEC_ERROR_ALREADY_EXISTS) {
    SetLast(ZVEC_OK);
    return true;
  }

  SetLast(code);
  LogZvecError("zvec_initialize", code);
  return false;
}

bool AddStringField(zvec_collection_schema_t *schema, const char *name) {
  zvec_field_schema_t *field =
      zvec_field_schema_create(name, ZVEC_DATA_TYPE_STRING, false, 0);
  if (field == nullptr) {
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return false;
  }
  const zvec_error_code_t code = zvec_collection_schema_add_field(schema, field);
  zvec_field_schema_destroy(field);
  if (code != ZVEC_OK) {
    LogZvecError(name, code);
    SetLast(code);
    return false;
  }
  return true;
}

bool AddVectorField(zvec_collection_schema_t *schema, int dimensions) {
  zvec_index_params_t *params = zvec_index_params_create(ZVEC_INDEX_TYPE_HNSW);
  if (params == nullptr) {
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return false;
  }

  zvec_error_code_t code =
      zvec_index_params_set_metric_type(params, ZVEC_METRIC_TYPE_L2);
  if (code == ZVEC_OK) {
    code = zvec_index_params_set_hnsw_params(params, 16, 200);
  }

  zvec_field_schema_t *field = nullptr;
  if (code == ZVEC_OK) {
    field = zvec_field_schema_create("embedding", ZVEC_DATA_TYPE_VECTOR_FP32,
                                     false,
                                     static_cast<uint32_t>(dimensions));
    if (field == nullptr) {
      code = ZVEC_ERROR_RESOURCE_EXHAUSTED;
    }
  }

  if (code == ZVEC_OK) {
    code = zvec_field_schema_set_index_params(field, params);
  }
  if (code == ZVEC_OK) {
    code = zvec_collection_schema_add_field(schema, field);
  }

  if (field != nullptr) {
    zvec_field_schema_destroy(field);
  }
  zvec_index_params_destroy(params);

  if (code != ZVEC_OK) {
    LogZvecError("embedding schema", code);
    SetLast(code);
    return false;
  }
  return true;
}

zvec_collection_schema_t *CreateSchema(int dimensions) {
  zvec_collection_schema_t *schema =
      zvec_collection_schema_create("pocketmind_vectors_v1");
  if (schema == nullptr) {
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return nullptr;
  }

  if (!AddStringField(schema, "id") ||
      !AddStringField(schema, "documentId") ||
      !AddStringField(schema, "text") ||
      !AddStringField(schema, "metadataJson") ||
      !AddVectorField(schema, dimensions)) {
    zvec_collection_schema_destroy(schema);
    return nullptr;
  }
  return schema;
}

jlong NativeCreate(JNIEnv *env, jclass, jstring path, jint dimensions) {
  if (!EnsureInitialized()) return 0;

  ScopedUtfChars path_chars(env, path);
  if (!path_chars.ok()) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return 0;
  }

  zvec_collection_schema_t *schema = CreateSchema(dimensions);
  if (schema == nullptr) return 0;

  zvec_collection_t *collection = nullptr;
  const zvec_error_code_t code = zvec_collection_create_and_open(
      path_chars.get(), schema, nullptr, &collection);
  zvec_collection_schema_destroy(schema);

  if (!IsOk(code)) {
    LogZvecError("zvec_collection_create_and_open", code);
    return 0;
  }
  return Handle(collection);
}

jlong NativeOpen(JNIEnv *env, jclass, jstring path) {
  if (!EnsureInitialized()) return 0;

  ScopedUtfChars path_chars(env, path);
  if (!path_chars.ok()) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return 0;
  }

  zvec_collection_t *collection = nullptr;
  const zvec_error_code_t code =
      zvec_collection_open(path_chars.get(), nullptr, &collection);
  if (!IsOk(code)) {
    LogZvecError("zvec_collection_open", code);
    return 0;
  }
  return Handle(collection);
}

zvec_error_code_t AddStringValue(zvec_doc_t *doc, const char *field,
                                 const char *value) {
  return zvec_doc_add_field_by_value(doc, field, ZVEC_DATA_TYPE_STRING, value,
                                     std::strlen(value));
}

jboolean NativeUpsert(JNIEnv *env, jobject, jlong handle, jstring id,
                      jstring document_id, jstring text, jfloatArray vector,
                      jstring metadata_json) {
  zvec_collection_t *collection = Collection(handle);
  if (collection == nullptr || vector == nullptr) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return JNI_FALSE;
  }

  ScopedUtfChars id_chars(env, id);
  ScopedUtfChars document_id_chars(env, document_id);
  ScopedUtfChars text_chars(env, text);
  ScopedUtfChars metadata_chars(env, metadata_json);
  if (!id_chars.ok() || !document_id_chars.ok() || !text_chars.ok() ||
      !metadata_chars.ok()) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return JNI_FALSE;
  }

  jsize vector_length = env->GetArrayLength(vector);
  jboolean copied = JNI_FALSE;
  jfloat *vector_values = env->GetFloatArrayElements(vector, &copied);
  if (vector_values == nullptr) {
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return JNI_FALSE;
  }

  zvec_doc_t *doc = zvec_doc_create();
  if (doc == nullptr) {
    env->ReleaseFloatArrayElements(vector, vector_values, JNI_ABORT);
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return JNI_FALSE;
  }

  zvec_doc_set_pk(doc, id_chars.get());
  zvec_error_code_t code = AddStringValue(doc, "id", id_chars.get());
  if (code == ZVEC_OK) {
    code = AddStringValue(doc, "documentId", document_id_chars.get());
  }
  if (code == ZVEC_OK) {
    code = AddStringValue(doc, "text", text_chars.get());
  }
  if (code == ZVEC_OK) {
    code = AddStringValue(doc, "metadataJson", metadata_chars.get());
  }
  if (code == ZVEC_OK) {
    code = zvec_doc_add_field_by_value(
        doc, "embedding", ZVEC_DATA_TYPE_VECTOR_FP32, vector_values,
        static_cast<size_t>(vector_length) * sizeof(float));
  }

  env->ReleaseFloatArrayElements(vector, vector_values, JNI_ABORT);

  size_t success_count = 0;
  size_t error_count = 0;
  if (code == ZVEC_OK) {
    const zvec_doc_t *docs[] = {doc};
    code = zvec_collection_upsert(collection, docs, 1, &success_count,
                                  &error_count);
  }
  zvec_doc_destroy(doc);

  if (code == ZVEC_OK && (success_count != 1 || error_count != 0)) {
    SetLast(ZVEC_ERROR_INTERNAL_ERROR);
    return JNI_FALSE;
  }
  if (!IsOk(code)) {
    LogZvecError("zvec_collection_upsert", code);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

jboolean NativeDelete(JNIEnv *env, jobject, jlong handle, jstring id) {
  zvec_collection_t *collection = Collection(handle);
  ScopedUtfChars id_chars(env, id);
  if (collection == nullptr || !id_chars.ok()) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return JNI_FALSE;
  }

  const char *pks[] = {id_chars.get()};
  size_t success_count = 0;
  size_t error_count = 0;
  const zvec_error_code_t code =
      zvec_collection_delete(collection, pks, 1, &success_count, &error_count);
  if (!IsOk(code)) {
    LogZvecError("zvec_collection_delete", code);
    return JNI_FALSE;
  }
  return success_count == 1 && error_count == 0 ? JNI_TRUE : JNI_FALSE;
}

std::string GetStringField(const zvec_doc_t *doc, const char *field) {
  const void *value = nullptr;
  size_t value_size = 0;
  const zvec_error_code_t code = zvec_doc_get_field_value_pointer(
      doc, field, ZVEC_DATA_TYPE_STRING, &value, &value_size);
  if (code != ZVEC_OK || value == nullptr) {
    return "";
  }
  return std::string(static_cast<const char *>(value), value_size);
}

jfloatArray GetVectorField(JNIEnv *env, const zvec_doc_t *doc) {
  const void *value = nullptr;
  size_t value_size = 0;
  const zvec_error_code_t code = zvec_doc_get_field_value_pointer(
      doc, "embedding", ZVEC_DATA_TYPE_VECTOR_FP32, &value, &value_size);
  if (code != ZVEC_OK || value == nullptr || value_size % sizeof(float) != 0) {
    return nullptr;
  }

  const auto length = static_cast<jsize>(value_size / sizeof(float));
  jfloatArray array = env->NewFloatArray(length);
  if (array == nullptr) return nullptr;
  env->SetFloatArrayRegion(array, 0, length,
                           static_cast<const jfloat *>(value));
  return array;
}

jobject NewRecord(JNIEnv *env, const zvec_doc_t *doc) {
  jclass record_class = env->FindClass(kRecordClass);
  if (record_class == nullptr) return nullptr;

  jmethodID constructor = env->GetMethodID(
      record_class, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[FLjava/lang/"
      "String;)V");
  if (constructor == nullptr) return nullptr;

  std::string id = GetStringField(doc, "id");
  if (id.empty()) {
    const char *pk = zvec_doc_get_pk_copy(doc);
    if (pk != nullptr) {
      id = pk;
      zvec_free((void *)pk);
    }
  }

  const std::string document_id = GetStringField(doc, "documentId");
  const std::string text = GetStringField(doc, "text");
  const std::string metadata_json = GetStringField(doc, "metadataJson");
  jfloatArray vector = GetVectorField(env, doc);
  if (vector == nullptr) return nullptr;

  jstring id_string = env->NewStringUTF(id.c_str());
  jstring document_id_string = env->NewStringUTF(document_id.c_str());
  jstring text_string = env->NewStringUTF(text.c_str());
  jstring metadata_string = env->NewStringUTF(metadata_json.c_str());
  if (id_string == nullptr || document_id_string == nullptr ||
      text_string == nullptr || metadata_string == nullptr) {
    return nullptr;
  }

  return env->NewObject(record_class, constructor, id_string,
                        document_id_string, text_string, vector,
                        metadata_string);
}

jobjectArray EmptyHits(JNIEnv *env) {
  jclass hit_class = env->FindClass(kHitClass);
  if (hit_class == nullptr) return nullptr;
  return env->NewObjectArray(0, hit_class, nullptr);
}

jobject NativeFetch(JNIEnv *env, jobject, jlong handle, jstring id) {
  zvec_collection_t *collection = Collection(handle);
  ScopedUtfChars id_chars(env, id);
  if (collection == nullptr || !id_chars.ok()) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return nullptr;
  }

  const char *pks[] = {id_chars.get()};
  const char *fields[] = {"id", "documentId", "text", "metadataJson",
                          "embedding"};
  zvec_doc_t **docs = nullptr;
  size_t found_count = 0;
  const zvec_error_code_t code =
      zvec_collection_fetch(collection, pks, 1, fields, 5, true, &docs,
                            &found_count);
  if (!IsOk(code)) {
    LogZvecError("zvec_collection_fetch", code);
    return nullptr;
  }

  jobject record = nullptr;
  if (docs != nullptr && found_count > 0) {
    record = NewRecord(env, docs[0]);
  }
  zvec_docs_free(docs, found_count);
  return record;
}

jobjectArray NativeQuery(JNIEnv *env, jobject, jlong handle, jfloatArray vector,
                         jint top_k) {
  zvec_collection_t *collection = Collection(handle);
  if (collection == nullptr || vector == nullptr || top_k <= 0) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return EmptyHits(env);
  }

  jsize vector_length = env->GetArrayLength(vector);
  jboolean copied = JNI_FALSE;
  jfloat *vector_values = env->GetFloatArrayElements(vector, &copied);
  if (vector_values == nullptr) {
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return EmptyHits(env);
  }

  zvec_vector_query_t *query = zvec_vector_query_create();
  if (query == nullptr) {
    env->ReleaseFloatArrayElements(vector, vector_values, JNI_ABORT);
    SetLast(ZVEC_ERROR_RESOURCE_EXHAUSTED);
    return EmptyHits(env);
  }

  zvec_error_code_t code = zvec_vector_query_set_field_name(query, "embedding");
  if (code == ZVEC_OK) {
    code = zvec_vector_query_set_query_vector(
        query, vector_values, static_cast<size_t>(vector_length) * sizeof(float));
  }
  if (code == ZVEC_OK) {
    code = zvec_vector_query_set_topk(query, top_k);
  }
  if (code == ZVEC_OK) {
    code = zvec_vector_query_set_include_vector(query, true);
  }

  env->ReleaseFloatArrayElements(vector, vector_values, JNI_ABORT);

  zvec_doc_t **results = nullptr;
  size_t result_count = 0;
  if (code == ZVEC_OK) {
    code = zvec_collection_query(collection, query, &results, &result_count);
  }
  zvec_vector_query_destroy(query);

  if (!IsOk(code)) {
    LogZvecError("zvec_collection_query", code);
    return EmptyHits(env);
  }

  jclass hit_class = env->FindClass(kHitClass);
  if (hit_class == nullptr) {
    zvec_docs_free(results, result_count);
    return nullptr;
  }
  jmethodID hit_constructor = env->GetMethodID(
      hit_class, "<init>",
      "(Lcom/bytedance/zgx/pocketmind/storage/ZvecNativeStore$Record;F)V");
  if (hit_constructor == nullptr) {
    zvec_docs_free(results, result_count);
    return nullptr;
  }

  auto array_length = static_cast<jsize>(result_count);
  jobjectArray hits = env->NewObjectArray(array_length, hit_class, nullptr);
  if (hits == nullptr) {
    zvec_docs_free(results, result_count);
    return nullptr;
  }

  for (jsize i = 0; i < array_length; ++i) {
    jobject record = NewRecord(env, results[i]);
    if (record == nullptr) continue;
    const float score = zvec_doc_get_score(results[i]);
    jobject hit = env->NewObject(hit_class, hit_constructor, record, score);
    if (hit != nullptr) {
      env->SetObjectArrayElement(hits, i, hit);
    }
  }

  zvec_docs_free(results, result_count);
  return hits;
}

jboolean NativeFlush(JNIEnv *, jobject, jlong handle) {
  zvec_collection_t *collection = Collection(handle);
  if (collection == nullptr) {
    SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
    return JNI_FALSE;
  }

  const zvec_error_code_t code = zvec_collection_flush(collection);
  if (!IsOk(code)) {
    LogZvecError("zvec_collection_flush", code);
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

jint NativeClose(JNIEnv *, jobject, jlong handle) {
  zvec_collection_t *collection = Collection(handle);
  if (collection == nullptr) {
    return SetLast(ZVEC_ERROR_INVALID_ARGUMENT);
  }
  const zvec_error_code_t code = zvec_collection_close(collection);
  if (code != ZVEC_OK) {
    LogZvecError("zvec_collection_close", code);
  }
  return SetLast(code);
}

jint NativeLastErrorCode(JNIEnv *, jclass) {
  return g_last_error_code.load();
}

}  // namespace

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  jclass store_class = env->FindClass(kStoreClass);
  if (store_class == nullptr) {
    return JNI_ERR;
  }

  static JNINativeMethod methods[] = {
      {"nativeCreate", "(Ljava/lang/String;I)J",
       reinterpret_cast<void *>(NativeCreate)},
      {"nativeOpen", "(Ljava/lang/String;)J",
       reinterpret_cast<void *>(NativeOpen)},
      {"nativeLastErrorCode", "()I",
       reinterpret_cast<void *>(NativeLastErrorCode)},
      {"nativeUpsert",
       "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[FLjava/lang/"
       "String;)Z",
       reinterpret_cast<void *>(NativeUpsert)},
      {"nativeDelete", "(JLjava/lang/String;)Z",
       reinterpret_cast<void *>(NativeDelete)},
      {"nativeFetch",
       "(JLjava/lang/String;)Lcom/bytedance/zgx/pocketmind/storage/"
       "ZvecNativeStore$Record;",
       reinterpret_cast<void *>(NativeFetch)},
      {"nativeQuery",
       "(J[FI)[Lcom/bytedance/zgx/pocketmind/storage/ZvecNativeStore$Hit;",
       reinterpret_cast<void *>(NativeQuery)},
      {"nativeFlush", "(J)Z", reinterpret_cast<void *>(NativeFlush)},
      {"nativeClose", "(J)I", reinterpret_cast<void *>(NativeClose)},
  };

  if (env->RegisterNatives(store_class, methods,
                           sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
