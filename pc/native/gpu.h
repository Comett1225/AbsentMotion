#pragma once

#include <memory>
#include "include/core/SkRefCnt.h"
class GrDirectContext;
struct DesktopGpu {
    virtual ~DesktopGpu() = default;
    virtual GrDirectContext* context() = 0;
};
std::unique_ptr<DesktopGpu> CreateDesktopGpu();
