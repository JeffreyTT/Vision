#!/usr/bin/env python3

'''
  A Simple mjpg stream http server for the Raspberry Pi Camera
  inspired by https://gist.github.com/n3wtron/4624820
'''
from http.server import BaseHTTPRequestHandler,HTTPServer
import io
import time
import picam
import algo
import os
import sys
import cv2
import logging
import argparse
import comm
import traceback

s_args=None
s_comm=None
s_first=True
s_jpgQuality = 80 # used by direct streaming, quality differs from opencv
s_jpgParam = [int(cv2.IMWRITE_JPEG_QUALITY), 50] # used by opencv
s_mainPage="""
<html><head>
<meta content="text/html;charset=utf-8" http-equiv="Content-Type">
<meta content="utf-8" http-equiv="encoding">
</head><body>
<img src="/cam.mjpg"/>
</body></html>"""

class CamHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        # we respond with a stream if the url ends in either .mjpg or .mjpeg
        # we select between direct or algorithm-filtered streams based
        # on the filename.  If the algostr (below) is "direct.mjpeg", we
        # get fast/raw/direct streaming. Otherwise the algostr is passed
        # to the algo entrypoint.
        if self.path.endswith('.mjpg') or self.path.endswith('.mjpeg'):
            algostr = os.path.basename(self.path).split(".")[0]
            self.send_response(200)
            self.send_header('Content-type',
                 'multipart/x-mixed-replace; boundary=--jpgboundary')
            self.end_headers()
            cam = None
            try:
                time.sleep(1) # wait for shutdown of alt stream
                cam = picam.PiCam(resolution=(640, 480), 
                                  framerate=60, auto=False)
                if not cam:
                    raise Exception("Hey no camera!")
                if algostr == "direct":
                    self.streamDirect(cam)
                else:
                    self.streamAlgo(cam, algostr)

            except Exception as e:
                print(e)

            print("done streaming ----------------------")
            if cam:
                cam.stop() # triggers PiCamera.close()
            else:
                print("(cam init problem)")
        else:
            self.send_response(200)
            self.send_header('Content-type','text/html')
            self.end_headers()
            self.wfile.write(bytes(s_mainPage,'utf-8'))
            return

    def streamDirect(self, cam):
        print("direct streaming")
        stream = io.BytesIO()
        try:
            for i in cam.cam.capture_continuous(stream, "jpeg",
                                            quality=s_jpgQuality,
                                            use_video_port=True):
                self.wfile.write(bytes("--jpgboundary\n",'utf-8'))
                self.send_header('Content-type','image/jpeg')
                self.send_header('Content-length',len(stream.getvalue()))
                self.end_headers()
                val = stream.getvalue()
                self.wfile.write(val)
                stream.seek(0)
                stream.truncate()
        finally:
            stream.seek(0)
            stream.truncate()

    def streamAlgo(self, cam, algoselector):
        global s_first
        print(algoselector + " algo streaming")
        cam.start()
        while True:
            camframe = cam.next()
            try:
                value,frame = algo.processFrame(camframe, algoselector,
                                            display=True, debug=False)
                if s_comm != None:
                    if value != None:
                        s_comm.updateVisionState("Acquired")
                        s_comm.UpdateTarget(value)
                    else:
                        s_comm.updateVisionState("Searching")
                    
                rc,jpg = cv2.imencode('.jpg', frame, s_jpgParam)
                if not rc:
                    continue
                if s_first:
                    print(jpg.size)
                    s_first = False
                self.wfile.write(bytes("--jpgboundary\n","utf-8"))
                self.send_header('Content-type','image/jpeg')
                self.send_header('Content-length', jpg.size)
                self.end_headers()
                self.wfile.write(jpg.tostring())
                time.sleep(0.05)

            except Exception as e:
                # Error handling; Critical for anything that happens in algo or below
                exc_info = sys.exc_info()
                print("algo exception: " + str(e))
                traceback.print_exception(*exc_info)
                
                break


def main():
  global s_args
  global s_comm
  try:
    parser = argparse.ArgumentParser(description="Start the picam streamer")
    parser.add_argument("--robot", dest="robot",
                        help="robot (none, localhost, roborio) [none]",
                        default="none")
    s_args = parser.parse_args()
    if s_args.robot != "none":
        if s_args.robot == "roborio":
            ip = "10.49.15.2"
        else:
            ip = s_args.robot
        s_comm = comm.Comm(ip)
    server = HTTPServer(('',5080),CamHandler)
    print ("server started")
    server.serve_forever()
  except KeyboardInterrupt:
    print ("aborting server")
    server.socket.close()

if __name__ == '__main__':
  main()

