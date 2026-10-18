#pragma once

#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace am {

struct XmlNode {
    std::string name, text;
    std::vector<std::pair<std::string, std::string>> attrs;
    std::vector<std::unique_ptr<XmlNode>> children;

    const char* attr(const char* key) const;
    const XmlNode* child(const char* name) const;
};

std::unique_ptr<XmlNode> ParseXml(const std::string& text, std::string* error);

}
