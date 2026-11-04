// Loaded into the emulator (DYLD_INSERT_LIBRARIES) for -gpu host on a Mac with no display attached.
// There the GPU counts as an "offline" renderer, and the emulator's GL backend asks NSOpenGLPixelFormat
// for a pixel format without NSOpenGLPFAAllowOfflineRenderers: it gets none, the guest's EGL fails, and
// surfaceflinger never runs (screencap times out, uiautomator has no window). This appends that flag to
// every request. It loads because the emulator is signed with allow-dyld-environment-variables and
// disable-library-validation; a new emulator without them ignores it (its log then names no Apple GPU).
// uat.py and dev/emu/up.sh build it: clang -dynamiclib -arch arm64 -framework AppKit -framework OpenGL.
#define GL_SILENCE_DEPRECATION
#import <AppKit/AppKit.h>
#import <objc/runtime.h>
static IMP orig;
// the attributes followed by a value (a 0 value is not the list's end)
static int valued(NSOpenGLPixelFormatAttribute a) {
  switch (a) {
    case NSOpenGLPFAAuxBuffers: case NSOpenGLPFAColorSize: case NSOpenGLPFAAlphaSize: case NSOpenGLPFADepthSize:
    case NSOpenGLPFAStencilSize: case NSOpenGLPFAAccumSize: case NSOpenGLPFARendererID: case NSOpenGLPFAScreenMask:
    case NSOpenGLPFASampleBuffers: case NSOpenGLPFASamples: case NSOpenGLPFAVirtualScreenCount:
    case NSOpenGLPFAOpenGLProfile:
      return 1;
    default:
      return 0;
  }
}
static id patched(id self, SEL _cmd, const NSOpenGLPixelFormatAttribute *a) {
  NSOpenGLPixelFormatAttribute b[128]; int n = 0, has = 0;
  for (int i = 0; a && a[i] && n < 124; i++) {
    if (a[i] == NSOpenGLPFAAllowOfflineRenderers) has = 1;
    b[n++] = a[i];
    if (valued(a[i])) { i++; b[n++] = a[i]; }
  }
  if (!has) b[n++] = NSOpenGLPFAAllowOfflineRenderers;
  b[n] = 0;
  return ((id(*)(id, SEL, const NSOpenGLPixelFormatAttribute *))orig)(self, _cmd, b);
}
__attribute__((constructor)) static void init(void) {
  Method m = class_getInstanceMethod([NSOpenGLPixelFormat class], @selector(initWithAttributes:));
  if (m) orig = method_setImplementation(m, (IMP)patched);
}
