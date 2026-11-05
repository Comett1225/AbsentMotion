#include <jni.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

std::string DesktopString(JNIEnv* env, jstring s);

namespace {

typedef uint32_t Bool;
struct Error { const char* name; const char* message; unsigned int flags; void* padding; };
struct Iter { void* fields[16]; };
struct Bus;
struct Message;

const char* const kService = "org.freedesktop.portal.Desktop";
const char* const kObject = "/org/freedesktop/portal/desktop";
const char* const kChooser = "org.freedesktop.portal.FileChooser";
const char* const kRequest = "org.freedesktop.portal.Request";

struct DBus {
    void (*error_init)(Error*);
    void (*error_free)(Error*);
    Bool (*threads_init)();
    Bus* (*bus_get_private)(int, Error*);
    void (*set_exit_on_disconnect)(Bus*, Bool);
    const char* (*unique_name)(Bus*);
    void (*close)(Bus*);
    void (*unref)(Bus*);
    void (*add_match)(Bus*, const char*, Error*);
    Message* (*new_call)(const char*, const char*, const char*, const char*);
    void (*message_unref)(Message*);
    Message* (*call)(Bus*, Message*, int, Error*);
    Bool (*read_write)(Bus*, int);
    Message* (*pop)(Bus*);
    Bool (*is_signal)(Message*, const char*, const char*);
    const char* (*path)(Message*);
    void (*append_init)(Message*, Iter*);
    Bool (*append)(Iter*, int, const void*);
    Bool (*open)(Iter*, int, const char*, Iter*);
    Bool (*close_container)(Iter*, Iter*);
    Bool (*read_init)(Message*, Iter*);
    int (*type)(Iter*);
    void (*get)(Iter*, void*);
    void (*recurse)(Iter*, Iter*);
    Bool (*next)(Iter*);

    template <typename T> static bool find(void* lib, T& out, const char* name) {
        out = reinterpret_cast<T>(dlsym(lib, name));
        return out != nullptr;
    }

    bool load() {
        void* lib = dlopen("libdbus-1.so.3", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return false;
        bool ok = find(lib, error_init, "dbus_error_init") && find(lib, error_free, "dbus_error_free")
            && find(lib, threads_init, "dbus_threads_init_default") && find(lib, bus_get_private, "dbus_bus_get_private")
            && find(lib, set_exit_on_disconnect, "dbus_connection_set_exit_on_disconnect") && find(lib, unique_name, "dbus_bus_get_unique_name")
            && find(lib, close, "dbus_connection_close") && find(lib, unref, "dbus_connection_unref") && find(lib, add_match, "dbus_bus_add_match")
            && find(lib, new_call, "dbus_message_new_method_call") && find(lib, message_unref, "dbus_message_unref")
            && find(lib, call, "dbus_connection_send_with_reply_and_block") && find(lib, read_write, "dbus_connection_read_write")
            && find(lib, pop, "dbus_connection_pop_message") && find(lib, is_signal, "dbus_message_is_signal") && find(lib, path, "dbus_message_get_path")
            && find(lib, append_init, "dbus_message_iter_init_append") && find(lib, append, "dbus_message_iter_append_basic")
            && find(lib, open, "dbus_message_iter_open_container") && find(lib, close_container, "dbus_message_iter_close_container")
            && find(lib, read_init, "dbus_message_iter_init") && find(lib, type, "dbus_message_iter_get_arg_type")
            && find(lib, get, "dbus_message_iter_get_basic") && find(lib, recurse, "dbus_message_iter_recurse") && find(lib, next, "dbus_message_iter_next");
        if (ok) threads_init();
        return ok;
    }

    bool entry(Iter* dict, const char* key, const char* signature, int kind, const void* value) {
        Iter item, variant;
        return open(dict, 'e', nullptr, &item) && append(&item, 's', &key) && open(&item, 'v', signature, &variant)
            && append(&variant, kind, value) && close_container(&item, &variant) && close_container(dict, &item);
    }

    bool flag(Iter* dict, const char* key, bool value) {
        Bool on = value ? 1 : 0;
        return entry(dict, key, "b", 'b', &on);
    }

    bool text(Iter* dict, const char* key, const std::string& value) {
        const char* chars = value.c_str();
        return entry(dict, key, "s", 's', &chars);
    }

    bool filters(Iter* dict, const std::vector<std::string>& specs) {
        Iter item, variant, list;
        const char* key = "filters";
        if (!open(dict, 'e', nullptr, &item) || !append(&item, 's', &key) || !open(&item, 'v', "a(sa(us))", &variant) || !open(&variant, 'a', "(sa(us))", &list)) return false;
        for (size_t i = 0; i + 1 < specs.size(); i += 2) {
            Iter filter, patterns;
            const char* name = specs[i].c_str();
            if (!open(&list, 'r', nullptr, &filter) || !append(&filter, 's', &name) || !open(&filter, 'a', "(us)", &patterns)) return false;
            const std::string& spec = specs[i + 1];
            for (size_t start = 0; start < spec.size();) {
                size_t end = spec.find(';', start);
                if (end == std::string::npos) end = spec.size();
                std::string glob = spec.substr(start, end - start);
                start = end + 1;
                if (glob.empty()) continue;
                Iter pattern;
                uint32_t kind = 0;
                const char* chars = glob.c_str();
                if (!open(&patterns, 'r', nullptr, &pattern) || !append(&pattern, 'u', &kind) || !append(&pattern, 's', &chars) || !close_container(&patterns, &pattern)) return false;
            }
            if (!close_container(&filter, &patterns) || !close_container(&list, &filter)) return false;
        }
        return close_container(&variant, &list) && close_container(&item, &variant) && close_container(dict, &item);
    }

    bool watch(Bus* bus, const std::string& handle) {
        std::string rule = std::string("type='signal',sender='") + kService + "',interface='" + kRequest + "',member='Response',path='" + handle + "'";
        Error error;
        error_init(&error);
        add_match(bus, rule.c_str(), &error);
        bool ok = error.name == nullptr;
        error_free(&error);
        return ok;
    }

    uint32_t version(Bus* bus) {
        Message* message = new_call(kService, kObject, "org.freedesktop.DBus.Properties", "Get");
        if (!message) return 0;
        Iter args;
        append_init(message, &args);
        const char* iface = kChooser;
        const char* property = "version";
        Error error;
        error_init(&error);
        Message* reply = append(&args, 's', &iface) && append(&args, 's', &property) ? call(bus, message, -1, &error) : nullptr;
        message_unref(message);
        error_free(&error);
        if (!reply) return 0;
        uint32_t found = 0;
        Iter it, inner;
        if (read_init(reply, &it) && type(&it) == 'v') {
            recurse(&it, &inner);
            if (type(&inner) == 'u') get(&inner, &found);
        }
        message_unref(reply);
        return found;
    }

    bool response(Message* message, std::vector<std::string>& uris) {
        Iter it;
        uint32_t code = 2;
        if (!read_init(message, &it) || type(&it) != 'u') return false;
        get(&it, &code);
        if (code == 1) return true;
        if (code != 0 || !next(&it) || type(&it) != 'a') return false;
        Iter dict;
        recurse(&it, &dict);
        for (; type(&dict) == 'e'; next(&dict)) {
            Iter item, value, list;
            const char* key = nullptr;
            recurse(&dict, &item);
            if (type(&item) != 's') continue;
            get(&item, &key);
            if (!key || strcmp(key, "uris") != 0 || !next(&item) || type(&item) != 'v') continue;
            recurse(&item, &value);
            if (type(&value) != 'a') continue;
            recurse(&value, &list);
            for (; type(&list) == 's'; next(&list)) {
                const char* uri = nullptr;
                get(&list, &uri);
                if (uri) uris.emplace_back(uri);
            }
        }
        return true;
    }

    bool ask(Bus* bus, const std::string& parent, int mode, bool multiple, const std::string& title, const std::string& name,
             const std::vector<std::string>& specs, std::vector<std::string>& uris) {
        if (version(bus) < (mode == 1 ? 3u : 1u)) return false;
        const char* unique = unique_name(bus);
        if (!unique) return false;
        std::string sender = unique[0] == ':' ? unique + 1 : unique;
        for (char& c : sender) if (c == '.') c = '_';
        std::string token = "absentmotion";
        std::string handle = std::string(kObject) + "/request/" + sender + "/" + token;
        if (!watch(bus, handle)) return false;
        Message* message = new_call(kService, kObject, kChooser, mode == 2 ? "SaveFile" : "OpenFile");
        if (!message) return false;
        Iter args, dict;
        append_init(message, &args);
        const char* window = parent.c_str();
        const char* heading = title.c_str();
        bool built = append(&args, 's', &window) && append(&args, 's', &heading) && open(&args, 'a', "{sv}", &dict)
            && text(&dict, "handle_token", token) && flag(&dict, "modal", true)
            && (mode == 2 ? name.empty() || text(&dict, "current_name", name) : flag(&dict, "multiple", mode == 0 && multiple))
            && (mode != 1 || flag(&dict, "directory", true))
            && (mode == 1 || specs.size() < 2 || filters(&dict, specs))
            && close_container(&args, &dict);
        Error error;
        error_init(&error);
        Message* reply = built ? call(bus, message, -1, &error) : nullptr;
        message_unref(message);
        error_free(&error);
        if (!reply) return false;
        const char* returned = nullptr;
        Iter it;
        if (read_init(reply, &it) && type(&it) == 'o') get(&it, &returned);
        std::string request = returned ? returned : handle;
        message_unref(reply);
        if (request != handle && !watch(bus, request)) return false;
        for (;;) {
            Message* signal = pop(bus);
            if (!signal) {
                if (!read_write(bus, -1)) return false;
                continue;
            }
            const char* from = path(signal);
            if (is_signal(signal, kRequest, "Response") && from && request == from) {
                bool ok = response(signal, uris);
                message_unref(signal);
                return ok;
            }
            message_unref(signal);
        }
    }

    bool pick(const std::string& parent, int mode, bool multiple, const std::string& title, const std::string& name,
              const std::vector<std::string>& specs, std::vector<std::string>& uris) {
        Error error;
        error_init(&error);
        Bus* bus = bus_get_private(0, &error);
        error_free(&error);
        if (!bus) return false;
        set_exit_on_disconnect(bus, 0);
        bool ok = ask(bus, parent, mode, multiple, title, name, specs, uris);
        close(bus);
        unref(bus);
        return ok;
    }
};

unsigned long toplevel(unsigned long window) {
    void* x = dlopen("libX11.so.6", RTLD_NOW | RTLD_LOCAL);
    if (!x) return window;
    auto connect = reinterpret_cast<void* (*)(const char*)>(dlsym(x, "XOpenDisplay"));
    auto disconnect = reinterpret_cast<int (*)(void*)>(dlsym(x, "XCloseDisplay"));
    auto atom = reinterpret_cast<unsigned long (*)(void*, const char*, int)>(dlsym(x, "XInternAtom"));
    auto tree = reinterpret_cast<int (*)(void*, unsigned long, unsigned long*, unsigned long*, unsigned long**, unsigned int*)>(dlsym(x, "XQueryTree"));
    auto property = reinterpret_cast<int (*)(void*, unsigned long, unsigned long, long, long, int, unsigned long, unsigned long*, int*, unsigned long*, unsigned long*, unsigned char**)>(dlsym(x, "XGetWindowProperty"));
    auto release = reinterpret_cast<int (*)(void*)>(dlsym(x, "XFree"));
    if (!connect || !disconnect || !atom || !tree || !property || !release) return window;
    void* display = connect(nullptr);
    if (!display) return window;
    unsigned long state = atom(display, "WM_STATE", 1);
    unsigned long found = window;
    for (unsigned long current = window; current;) {
        unsigned long kind = 0, items = 0, left = 0;
        int format = 0;
        unsigned char* data = nullptr;
        bool managed = state && property(display, current, state, 0, 0, 0, 0, &kind, &format, &items, &left, &data) == 0 && kind != 0;
        if (data) release(data);
        if (managed) {
            found = current;
            break;
        }
        unsigned long root = 0, parent = 0;
        unsigned long* children = nullptr;
        unsigned int count = 0;
        if (!tree(display, current, &root, &parent, &children, &count)) break;
        if (children) release(children);
        found = current;
        if (parent == root) break;
        current = parent;
    }
    disconnect(display);
    return found;
}

std::string ascii(const std::string& text) {
    std::string out;
    char hex[4];
    for (unsigned char c : text) {
        if (c >= 0x20 && c < 0x7f) {
            out += (char)c;
        } else {
            snprintf(hex, sizeof hex, "%%%02X", c);
            out += hex;
        }
    }
    return out;
}

}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_app_absentmotion_pc_engine_DesktopNative_pickFiles(JNIEnv* e, jobject, jlong owner, jint mode, jboolean multiple, jstring title, jstring name, jobjectArray filters) {
    static DBus dbus;
    static bool loaded = dbus.load();
    if (!loaded) return nullptr;
    std::vector<std::string> specs;
    jsize count = filters ? e->GetArrayLength(filters) : 0;
    for (jsize i = 0; i < count; i++) {
        jstring s = (jstring)e->GetObjectArrayElement(filters, i);
        specs.push_back(DesktopString(e, s));
        e->DeleteLocalRef(s);
    }
    char parent[32] = "";
    if (owner) snprintf(parent, sizeof parent, "x11:%lx", toplevel((unsigned long)owner));
    std::vector<std::string> uris;
    if (!dbus.pick(parent, mode, multiple == JNI_TRUE, DesktopString(e, title), DesktopString(e, name), specs, uris)) return nullptr;
    jobjectArray result = e->NewObjectArray((jsize)uris.size(), e->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < uris.size(); i++) {
        jstring uri = e->NewStringUTF(ascii(uris[i]).c_str());
        e->SetObjectArrayElement(result, (jsize)i, uri);
        e->DeleteLocalRef(uri);
    }
    return result;
}
