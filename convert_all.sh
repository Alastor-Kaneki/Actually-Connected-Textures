#!/bin/sh
# Convert the glass packs (unpacked Java packs under $PACKS, default ./packs) into
#   out/packs/<slug>.mcpack                  normal packs (fall back to plain glass without the mod)
#   out/packs_direct/<slug>_direct.mcpack    fallback layout, only if the normal packs don't connect
# The packs themselves aren't in this repo: they belong to their authors.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
C=$HERE/converter/ctm_convert.py
PACKS=${PACKS:-$HERE/packs}
O=${OUT:-$HERE/out}/packs
D=${OUT:-$HERE/out}/packs_direct
mkdir -p "$O" "$D"
conv() {
  python3 -I "$C" "$PACKS/$1" "$O/$3.mcpack" --name "$2" --slug "$3" > "$O/$3.log" 2>&1 || { echo "FAILED $1"; cat "$O/$3.log"; }
  python3 -I "$C" "$PACKS/$1" "$D/$3_direct.mcpack" --name "$2" --slug "$3" --direct > "$D/$3.log" 2>&1 || { echo "FAILED direct $1"; cat "$D/$3.log"; }
  echo "$3: $(grep -c '"ctm-' "$O/$3.log") blocks"
}
conv "Better-Connected-Glass" "Better Connected Glass (Penu)" better_connected_glass_penu
conv "Better-Connected-Tinted-Glass" "Better Connected Tinted Glass" better_connected_tinted_glass
conv "BetterGlassTexture" "More Clear Glass" more_clear_glass
conv "Better_Glass" "Better Glass" better_glass
conv "CleanConnectedGlass_CCG_0.1.0" "Clean Connected Glass CCG" clean_connected_glass_ccg
conv "Clean_Connected_Glass_1.20-26.x" "Clean Connected Glass" clean_connected_glass
conv "ClearPane_26_3" "ClearPane" clearpane
conv "Glass" "Glass" glass
conv "better-colored-connected-glass-by-kovaszos-uborka" "Better Colored Connected Glass (kovaszos_uborka)" better_colored_connected_glass_kovaszos
conv "better-connected-glass-by-kovaszos-uborka" "Better Connected Glass (kovaszos_uborka)" better_connected_glass_kovaszos
conv "better-glass" "Better Glass (Yami_)" better_glass_yami
conv "translucent-glass-0.3.0" "Translucent Glass" translucent_glass
