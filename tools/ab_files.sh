#!/usr/bin/env bash
set -euo pipefail
out=${1:?usage: tools/ab_files.sh <out folder> [<windows media samples folder>]}
samples=${2:-}
mkdir -p "$out"
cd "$out"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
sound() { echo "aevalsrc=0.4*sin(2*PI*(220+110*sin(2*PI*0.25*t))*t)|0.4*sin(2*PI*(330+80*sin(2*PI*0.4*t))*t):s=$1:d=$2"; }

[ -f a1_hevc_4k_hlg.mp4 ] || ff -f lavfi -i testsrc2=s=3840x2160:r=30:d=4 -f lavfi -i "$(sound 48000 4)" \
  -c:v libx265 -preset fast -pix_fmt yuv420p10le -x265-params "keyint=30:hdr-opt=0:colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc" \
  -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -tag:v hvc1 -c:a aac -b:a 192k a1_hevc_4k_hlg.mp4
[ -f a2_h264_1080p60.mp4 ] || ff -f lavfi -i testsrc2=s=1920x1080:r=60:d=5 -f lavfi -i "$(sound 48000 5)" \
  -c:v libx264 -preset fast -profile:v high -g 60 -bf 2 -pix_fmt yuv420p -c:a aac -b:a 192k a2_h264_1080p60.mp4
[ -f a3_vfr_screen.mp4 ] || ff -f lavfi -i testsrc2=s=1080x1920:r=60:d=6 \
  -vf "select='not(mod(n\,3))+eq(mod(n\,7)\,1)+between(n\,200\,260)'" -fps_mode vfr -c:v libx264 -preset fast -bf 0 -g 120 -pix_fmt yuv420p -video_track_timescale 90000 a3_vfr_screen.mp4
[ -f a4_vp9.webm ] || ff -f lavfi -i testsrc2=s=1280x720:r=30:d=5 -f lavfi -i "$(sound 48000 5)" \
  -c:v libvpx-vp9 -b:v 2M -g 60 -deadline good -cpu-used 4 -row-mt 1 -c:a libopus -b:a 128k a4_vp9.webm
[ -f a5_vp9_alpha.webm ] || ff -f lavfi -i testsrc2=s=1280x720:r=30:d=3 \
  -vf "format=yuva420p,geq=lum='lum(X,Y)':cb='cb(X,Y)':cr='cr(X,Y)':a='if(gt(X,W/2),255,80)'" \
  -c:v libvpx-vp9 -b:v 2M -g 60 -auto-alt-ref 0 -deadline good -cpu-used 4 a5_vp9_alpha.webm
[ -f a6_av1.mp4 ] || ff -f lavfi -i testsrc2=s=1280x720:r=30:d=5 -f lavfi -i "$(sound 48000 5)" \
  -c:v libsvtav1 -preset 8 -g 60 -pix_fmt yuv420p -c:a aac -b:a 160k a6_av1.mp4
[ -f a7_hevc_1080p30.mp4 ] || ff -f lavfi -i testsrc2=s=1920x1080:r=30:d=5 -f lavfi -i "$(sound 48000 5)" \
  -c:v libx265 -preset fast -pix_fmt yuv420p -x265-params keyint=30 -tag:v hvc1 -c:a aac -b:a 192k a7_hevc_1080p30.mp4
[ -f b1_aac.m4a ] || ff -f lavfi -i "$(sound 48000 60)" -c:a aac -b:a 192k b1_aac.m4a
[ -f b2_opus.ogg ] || ff -f lavfi -i "$(sound 48000 60)" -c:a libopus -b:a 128k b2_opus.ogg
[ -f b3_mp3.mp3 ] || ff -f lavfi -i "$(sound 44100 60)" -c:a libmp3lame -b:a 192k b3_mp3.mp3
[ -f b4_flac.flac ] || ff -f lavfi -i "$(sound 48000 60)" -c:a flac b4_flac.flac
[ -f c1_divx3.avi ] || ff -f lavfi -i testsrc2=s=640x480:r=25:d=6 -f lavfi -i "$(sound 44100 6)" \
  -c:v msmpeg4 -q:v 4 -g 50 -c:a libmp3lame -b:a 128k c1_divx3.avi
[ -f c2_xvid.avi ] || ff -f lavfi -i testsrc2=s=640x480:r=25:d=6 -f lavfi -i "$(sound 44100 6)" \
  -c:v mpeg4 -vtag XVID -q:v 4 -g 50 -bf 2 -c:a libmp3lame -b:a 128k c2_xvid.avi
[ -f c6_wma2.wma ] || ff -f lavfi -i "$(sound 44100 60)" -c:a wmav2 -b:a 128k c6_wma2.wma
[ -f c9_mpeg2.ts ] || ff -f lavfi -i testsrc2=s=720x576:r=25:d=6 -f lavfi -i "$(sound 48000 6)" \
  -c:v mpeg2video -b:v 6M -g 12 -bf 2 -c:a aac -b:a 192k c9_mpeg2.ts
[ -f d1_mkv_ac3.mkv ] || ff -f lavfi -i testsrc2=s=1280x720:r=25:d=6 -f lavfi -i "$(sound 48000 6)" \
  -c:v libx264 -preset fast -g 50 -bf 2 -pix_fmt yuv420p -c:a ac3 -b:a 384k -ac 6 d1_mkv_ac3.mkv
[ -f e1_mpeg2_ps.mpg ] || ff -f lavfi -i testsrc2=s=720x576:r=25:d=6 -f lavfi -i "$(sound 48000 6)" \
  -c:v mpeg2video -b:v 6M -g 12 -bf 2 -c:a mp2 -b:a 192k -f vob e1_mpeg2_ps.mpg
if [ -n "$samples" ]; then
  cp -n "$samples/sample-wmv7.wmv" c3_wmv7.wmv
  cp -n "$samples/sample-wmv8.1.wmv" c4_wmv8.wmv
  cp -n "$samples/sample-wmv9-main-MP@LL.wmv" c5_wmv9.wmv
  cp -n "$samples/Classical_44_16_6_256000_0_20.wma" c7_wmapro.wma
  cp -n "$samples/luckynight.wma" c8_wmalossless.wma
fi
ls -la
