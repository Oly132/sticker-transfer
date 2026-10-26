#include <jni.h>
#include <cstdlib>
#include <node.h>
#include <string>
#include <vector>

extern "C" JNIEXPORT jint JNICALL
Java_com_example_stickerpackbuilder_sync_PhoneRuntime_launch(JNIEnv* env, jobject, jstring script, jstring temp) {
    const char* path = env->GetStringUTFChars(script, nullptr);
    const char* tmp = env->GetStringUTFChars(temp, nullptr);
    std::string savedPath(path);
    setenv("TMPDIR", tmp, 1);
    env->ReleaseStringUTFChars(script, path);
    env->ReleaseStringUTFChars(temp, tmp);
    std::vector<char> argvStorage(5 + savedPath.size() + 1);
    std::copy_n("node", 5, argvStorage.data());
    std::copy(savedPath.begin(), savedPath.end(), argvStorage.begin() + 5);
    char* arguments[] = {argvStorage.data(), argvStorage.data() + 5};
    return node::Start(2, arguments);
}
