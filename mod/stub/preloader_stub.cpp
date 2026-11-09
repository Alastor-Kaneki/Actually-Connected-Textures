// Link-time stand-in for LeviLauncher's libpreloader.so. Only the symbols the
// mod imports are defined; the real library provides them at runtime.
#include "pl/memory/Hook.hpp"
#include "pl/memory/Signature.hpp"
#include "pl/ModMenu.hpp"
namespace pl::memory {
int hook(FuncPtr, FuncPtr, FuncPtr*, HookPriority) { return -1; }
bool unhook(FuncPtr, FuncPtr) { return false; }
uintptr_t resolveSignature(std::string_view, std::string_view) { return 0; }
std::unordered_map<std::string, uintptr_t> resolveSignatures(std::span<const std::string>, std::string_view) { return {}; }
}
namespace pl::modmenu {
bool registerModule(const ModuleInfo&) { return false; }
void unregisterModule(std::string_view) {}
}
