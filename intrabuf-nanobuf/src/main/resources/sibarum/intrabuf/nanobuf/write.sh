nbw()(u=$(printf '\37');r=$(printf '\36');i=0;for a;do case $a in *"$u"*|*"$r"*)echo "nanobuf: field $i of $1 contains 0x1E or 0x1F">&2;exit 1;esac;i=$((i+1));done;IFS=$u;printf '%s\36\n' "$*")
