nbw(){ local a i=0;for a;do [[ $a = *[$'\36\37']* ]]&&{ echo "nanobuf: field $i of $1 contains 0x1E or 0x1F">&2;return 1;};((++i));done;local IFS=$'\37';printf '%s\36\n' "$*";}
