import copy
import ctypes
import json
import os
import time

import yt_dlp
from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError, register_preference, register_provider
from yt_dlp.utils import DownloadCancelled

_lib = None
_last = {}
_warned = []
_KEEP = 20 * 60


def call(message):
    global _lib
    if _lib is None:
        lib = ctypes.CDLL('libampy.so')
        lib.am_call.restype = ctypes.c_void_p
        lib.am_call.argtypes = [ctypes.c_char_p]
        lib.am_free.argtypes = [ctypes.c_void_p]
        _lib = lib
    pointer = _lib.am_call(json.dumps(message).encode('utf-8'))
    if not pointer:
        return ''
    try:
        return ctypes.string_at(pointer).decode('utf-8', 'replace')
    finally:
        _lib.am_free(pointer)


@register_provider
class AbsentWebViewJCP(EJSBaseJCP):
    PROVIDER_NAME = 'webview'
    JS_RUNTIME_NAME = 'webview'

    def is_available(self):
        return self._available

    def _run_js_runtime(self, stdin, /):
        answer = json.loads(call({'op': 'js', 'code': stdin}) or '{}')
        if 'out' not in answer:
            raise JsChallengeProviderError(answer.get('error') or 'the JavaScript engine gave no answer')
        return answer['out']


@register_preference(AbsentWebViewJCP)
def _prefer(provider, requests):
    return 1000


class _Logger:
    def debug(self, text):
        pass

    def info(self, text):
        pass

    def warning(self, text):
        _warned.append(text)
        if 'Requested format is not available' not in text and 'ffmpeg' not in text:
            call({'op': 'log', 'level': 'warning', 'text': text})

    def error(self, text):
        call({'op': 'log', 'level': 'error', 'text': text})


class _Progress:
    def __init__(self):
        self.last = 0.0

    def __call__(self, status):
        now = time.monotonic()
        finished = status.get('status') == 'finished'
        if not finished and now - self.last < 0.15:
            return
        self.last = now
        answer = call({
            'op': 'progress',
            'done': status.get('downloaded_bytes') or 0,
            'total': status.get('total_bytes') or status.get('total_bytes_estimate') or 0,
        })
        if answer == 'stop':
            raise DownloadCancelled('cancelled')


def _options(request, **extra):
    options = {
        'quiet': True,
        'no_warnings': False,
        'noprogress': True,
        'logger': _Logger(),
        'color': {'stdout': 'no_color', 'stderr': 'no_color'},
        'noplaylist': True,
        'playlist_items': '1:30',
        'ignore_no_formats_error': True,
        'js_runtimes': {},
        'cachedir': request['cache'],
        'socket_timeout': 20,
        'retries': 3,
        'fragment_retries': 5,
        'extractor_retries': 2,
        'concurrent_fragment_downloads': 4,
        'overwrites': True,
        'format': lambda context: [],
    }
    options.update(extra)
    return options


def _entries(info):
    if info.get('_type') == 'playlist':
        found = [entry for entry in (info.get('entries') or []) if entry]
        for entry in found:
            for key in ('extractor', 'extractor_key', 'webpage_url', 'original_url', 'webpage_url_basename', 'webpage_url_domain'):
                if entry.get(key) is None and info.get(key) is not None:
                    entry[key] = info[key]
        return found
    return [info]


def _formats(entry):
    found = entry.get('formats')
    if found is None:
        found = [entry] if entry.get('url') else []
    return [item for item in found if not (item.get('vcodec') == 'none' and item.get('acodec') == 'none') and item.get('ext') != 'mhtml' and not item.get('has_drm')]


def _unique(urls, limit):
    found = []
    for url in urls:
        if url and url not in found:
            found.append(url)
    return found[:limit]


def _previews(entry):
    pictures = [item for item in (entry.get('thumbnails') or []) if item.get('url')]
    sized = sorted((item for item in pictures if 200 <= (item.get('width') or 0) <= 800), key=lambda item: item['width'])
    return _unique([entry.get('thumbnail')] + [item['url'] for item in sized] + [item['url'] for item in reversed(pictures)], 5)


def _picture(entry):
    pictures = [item['url'] for item in (entry.get('thumbnails') or []) if item.get('url')]
    return _unique(list(reversed(pictures))[:2] + [entry.get('thumbnail')], 3)


def _describe(entry):
    formats = _formats(entry)
    rows = [{
        'id': item.get('format_id') or '0',
        'ext': item.get('ext'),
        'v': item.get('vcodec'),
        'a': item.get('acodec'),
        'w': item.get('width'),
        'h': item.get('height'),
        'fps': item.get('fps'),
        'tbr': item.get('tbr'),
        'abr': item.get('abr'),
        'size': item.get('filesize') or item.get('filesize_approx'),
        'proto': item.get('protocol'),
        'ch': item.get('audio_channels'),
        'note': item.get('format_note'),
    } for item in formats]
    picture = [] if rows else _picture(entry)
    title = entry.get('title') or ''
    caption = (entry.get('description') or '').strip().split('\n')[0][:120]
    if caption and (not title or title.startswith(('Video by ', 'Post by ', 'Photo by '))):
        title = caption
    return {
        'title': title,
        'duration': entry.get('duration'),
        'thumbnails': _previews(entry),
        'formats': rows,
        'pictures': [picture] if picture else [],
    }


def _check(request):
    _warned.clear()
    with yt_dlp.YoutubeDL(_options(request)) as ydl:
        info = ydl.extract_info(request['url'], download=False)
        info = ydl.sanitize_info(info)
    entries = _entries(info)
    if entries and not any(_formats(entry) for entry in entries):
        bot = next((text for text in _warned if 'not a bot' in text or 'HTTP Error 429' in text), None)
        if bot:
            raise RuntimeError('not a bot: ' + bot)
    _last.clear()
    _last.update(url=request['url'], entries=entries, at=time.monotonic())
    items = [_describe(entry) for entry in entries]
    title = info.get('title') or ''
    if len(items) == 1 or not title or title.startswith(('Video by ', 'Post by ', 'Photo by ')):
        title = next((item['title'] for item in items if item['title']), title)
    return {
        'title': title,
        'thumbnails': _previews(info) or next((item['thumbnails'] for item in items if item['thumbnails']), []),
        'site': info.get('extractor_key'),
        'items': items,
    }


def _entry(request):
    if _last.get('url') != request['url'] or time.monotonic() - _last.get('at', 0) > _KEEP:
        _check(request)
    return copy.deepcopy(_last['entries'][request['item']])


def _fetch(request):
    entry = _entry(request)
    wanted = request['format']

    def choose(context):
        formats = context['formats']
        exact = [item for item in formats if item.get('format_id') == wanted]
        return exact[:1] or (formats[:1] if len(formats) == 1 else [])

    options = _options(request, format=choose, outtmpl={'default': request['out'] + '.%(ext)s'}, progress_hooks=[_Progress()])
    with yt_dlp.YoutubeDL(options) as ydl:
        done = ydl.process_ie_result(entry, download=True)
        path = next((item.get('filepath') for item in done.get('requested_downloads') or [] if item.get('filepath')), None)
    if not path or not os.path.exists(path):
        raise RuntimeError('the download left no file')
    return {'path': path}


def _download_picture(request):
    progress = _Progress()
    with yt_dlp.YoutubeDL(_options(request)) as ydl:
        response = None
        for source in request['srcs']:
            try:
                response = ydl.urlopen(source)
                break
            except Exception as error:
                call({'op': 'log', 'level': 'warning', 'text': f'picture failed: {error}'})
        if response is None:
            raise RuntimeError('no picture could be downloaded')
        total = int(response.headers.get('Content-Length') or 0)
        done = 0
        with open(request['out'], 'wb') as output:
            while True:
                chunk = response.read(1 << 16)
                if not chunk:
                    break
                output.write(chunk)
                done += len(chunk)
                progress({'status': 'downloading', 'downloaded_bytes': done, 'total_bytes': total})
    return {'path': request['out']}


_OPS = {'check': _check, 'fetch': _fetch, 'picture': _download_picture}


def serve():
    try:
        request = json.loads(call({'op': 'request'}))
        result = {'ok': True, 'data': _OPS[request['op']](request)}
    except DownloadCancelled:
        result = {'ok': False, 'cancelled': True, 'error': 'cancelled'}
    except BaseException as error:
        result = {'ok': False, 'error': str(error) or type(error).__name__}
    call({'op': 'result', 'result': result})
