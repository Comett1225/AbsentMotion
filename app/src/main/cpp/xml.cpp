#include "xml.h"

#include <cstring>

#include "expat.h"

namespace am {

const char* XmlNode::attr(const char* key) const {
    for (const auto& a : attrs) {
        if (a.first == key) return a.second.c_str();
    }
    return nullptr;
}

const XmlNode* XmlNode::child(const char* childName) const {
    for (const auto& c : children) {
        if (c->name == childName) return c.get();
    }
    return nullptr;
}

namespace {
struct Builder {
    std::unique_ptr<XmlNode> root;
    std::vector<XmlNode*> stack;
};

void XMLCALL OnStart(void* user, const XML_Char* name, const XML_Char** attrs) {
    auto* b = static_cast<Builder*>(user);
    auto node = std::make_unique<XmlNode>();
    node->name = name;
    for (int i = 0; attrs[i]; i += 2) node->attrs.emplace_back(attrs[i], attrs[i + 1]);
    XmlNode* raw = node.get();
    if (b->stack.empty()) b->root = std::move(node); else b->stack.back()->children.push_back(std::move(node));
    b->stack.push_back(raw);
}

void XMLCALL OnText(void* user, const XML_Char* text, int length) {
    auto* b = static_cast<Builder*>(user);
    if (!b->stack.empty()) b->stack.back()->text.append(text, length);
}

void XMLCALL OnEnd(void* user, const XML_Char*) { static_cast<Builder*>(user)->stack.pop_back(); }
}

std::unique_ptr<XmlNode> ParseXml(const std::string& text, std::string* error) {
    Builder builder;
    XML_Parser parser = XML_ParserCreate(nullptr);
    XML_SetUserData(parser, &builder);
    XML_SetElementHandler(parser, OnStart, OnEnd);
    XML_SetCharacterDataHandler(parser, OnText);
    const bool ok = XML_Parse(parser, text.data(), (int)text.size(), XML_TRUE) != XML_STATUS_ERROR;
    if (!ok && error) {
        *error = std::string(XML_ErrorString(XML_GetErrorCode(parser))) + " at line " +
                 std::to_string(XML_GetCurrentLineNumber(parser));
    }
    XML_ParserFree(parser);
    return ok ? std::move(builder.root) : nullptr;
}

}
