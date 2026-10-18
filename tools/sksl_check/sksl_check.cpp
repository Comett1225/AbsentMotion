#include "src/sksl/SkSLCompiler.h"
#include "src/sksl/SkSLProgramKind.h"
#include "src/sksl/SkSLProgramSettings.h"
#include "src/sksl/ir/SkSLFunctionDeclaration.h"
#include "src/sksl/ir/SkSLProgram.h"

#include <cstdarg>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>

void SkDebugf(const char format[], ...) {
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
}

int main(int argc, char** argv) {
    int failures = 0;
    for (int i = 1; i < argc; ++i) {
        std::ifstream in(argv[i]);
        std::stringstream text;
        text << in.rdbuf();
        SkSL::Compiler compiler;
        SkSL::ProgramSettings settings;
        settings.fInlineThreshold = 0;
        settings.fOptimize = true;
        settings.fMaxVersionAllowed = SkSL::Version::k300;
        settings.fUseMemoryPool = false;
        auto program = compiler.convertProgram(SkSL::ProgramKind::kRuntimeShader, text.str(), settings);
        if (program && !program->getFunction("main")) {
            std::cout << "ERR " << argv[i] << "\nmissing 'main' function\n";
            ++failures;
        } else if (!program) {
            std::cout << "ERR " << argv[i] << "\n" << compiler.errorText() << "\n";
            ++failures;
        } else {
            std::cout << "OK " << argv[i] << "\n";
        }
    }
    return failures ? 1 : 0;
}
