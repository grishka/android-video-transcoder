package me.grishka.videotranscoder;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by grishka on 21.01.15.
 */
public class VideoConverter {

	private static final String TAG="videoconverter";
	private ArrayList<PendingSample> pendingSamples=new ArrayList<>();
	private String srcFile, dstFile;
	private int videoBitrate, audioBitrate, videoSize;
	private boolean canceled, running;
	private Callback callback;
	private boolean isQualcomm;
	private Context context;

	public VideoConverter(String srcFile, String dstFile, int videoBitrate, int audioBitrate, int videoSize, Callback callback, final Activity act){
		this.srcFile=srcFile;
		this.dstFile=dstFile;
		this.videoBitrate=videoBitrate;
		this.audioBitrate=audioBitrate;
		this.videoSize=videoSize;
		this.callback=callback;
		context=act;
		if(this.srcFile.startsWith("/"))
			this.srcFile="file://"+this.srcFile;

		//if(Build.VERSION.SDK_INT<Build.VERSION_CODES.LOLLIPOP) {
			final GLSurfaceView gsv = new GLSurfaceView(act);
			gsv.setRenderer(new GLSurfaceView.Renderer() {
				@Override
				public void onSurfaceCreated(GL10 gl, EGLConfig config) {
					isQualcomm = "Qualcomm".equals(gl.glGetString(GL10.GL_VENDOR));
					act.runOnUiThread(new Runnable() {
						@Override
						public void run() {
							((FrameLayout) act.getWindow().getDecorView()).removeView(gsv);
						}
					});
					VideoConverter.this.callback.onReady();
				}

				@Override
				public void onSurfaceChanged(GL10 gl, int width, int height) {

				}

				@Override
				public void onDrawFrame(GL10 gl) {

				}
			});
			((FrameLayout) act.getWindow().getDecorView()).addView(gsv);
		/*}else{
			act.getWindow().getDecorView().post(new Runnable() {
				@Override
				public void run() {
					VideoConverter.this.callback.onReady();
				}
			});
		}*/
	}

	public void start(){
		if(running) return;
		new Thread(new ConverterRunnable()).start();
	}

	public void cancel(){
		canceled=true;
	}

	private class ConverterRunnable implements Runnable{
		@Override
		public void run() {
			running=true;
			try {
				MediaMetadataRetriever mmr=new MediaMetadataRetriever();
				mmr.setDataSource(context, Uri.parse(srcFile));
				int rotation=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
				mmr.release();
				MediaExtractor extractor = new MediaExtractor();
				extractor.setDataSource(context, Uri.parse(srcFile), null);
				new File(dstFile).delete();
				MediaMuxer muxer=new MediaMuxer(dstFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				muxer.setOrientationHint(rotation);
				int numTracks=extractor.getTrackCount();
				int audioTrack=-1, videoTrack=-1;
				for(int i=0;i<numTracks;i++){
					MediaFormat f=extractor.getTrackFormat(i);
					Log.i(TAG, "Track " + i + " = " + f);
					if(f.getString(MediaFormat.KEY_MIME).startsWith("video/")){
						if(videoTrack==-1){
							Log.i(TAG, "Selected video track "+i);
							videoTrack=i;
							//extractor.selectTrack(i);
						}
					}else if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){
						if(audioTrack==-1){
							Log.i(TAG, "Selected audio track "+i);
							audioTrack=i;
							//extractor.selectTrack(i);
						}
					}
				}
				if(audioTrack==-1 || videoTrack==-1){
					Log.e(TAG, "Some tracks are missing");
					callback.onConversionFailed("File should contain both audio and video");
					extractor.release();
					return;
				}

				// detect FPS
				float fps=0;
				long lastFramePTS=-1;
				extractor.selectTrack(videoTrack);
				for(int i=0;i<100;i++){
					fps++;
					if(extractor.getSampleTime()>1000000) break;
					lastFramePTS=extractor.getSampleTime();
					extractor.advance();
				}
				fps*=(lastFramePTS/1000000f);
				fps=Math.round(fps);
				Log.i(TAG, "Detected FPS is "+fps);
				extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
				extractor.selectTrack(audioTrack);

				MediaFormat audioFormat=extractor.getTrackFormat(audioTrack);
				MediaFormat videoFormat=extractor.getTrackFormat(videoTrack);
				int videoW=videoFormat.getInteger(MediaFormat.KEY_WIDTH);
				int videoH=videoFormat.getInteger(MediaFormat.KEY_HEIGHT);

				int maxSize=Math.max(videoW, videoH);
				int sampleSize=Math.round(maxSize/videoSize);
				long lastTS=0;
				int yuvDataSize=videoW*videoH*2;
				int resizedYuvDataSize=(videoW/sampleSize)*(videoH/sampleSize)*2;

				MediaCodec audioDecoder=MediaCodec.createDecoderByType(audioFormat.getString(MediaFormat.KEY_MIME));
				audioDecoder.configure(audioFormat, null, null, 0);

				MediaCodec videoDecoder=MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME));
				videoDecoder.configure(videoFormat, null, null, 0);

				MediaCodecInfo vInfo=videoDecoder.getCodecInfo();
				MediaCodecInfo.CodecCapabilities caps=vInfo.getCapabilitiesForType(videoFormat.getString(MediaFormat.KEY_MIME));

				MediaCodec audioEncoder=MediaCodec.createEncoderByType("audio/mp4a-latm");
				MediaFormat audioEncFormat=MediaFormat.createAudioFormat("audio/mp4a-latm", audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
				audioEncFormat.setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate*1024);
				audioEncoder.configure(audioEncFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

				MediaCodec videoEncoder=MediaCodec.createEncoderByType("video/avc");
				MediaFormat videoEncFormat=MediaFormat.createVideoFormat("video/avc", videoW/sampleSize, videoH/sampleSize);
				videoEncFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate*1024);
				videoEncFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
				videoEncFormat.setFloat(MediaFormat.KEY_FRAME_RATE, fps);
				videoEncFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
				videoEncoder.configure(videoEncFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);


				videoEncoder.start();
				ByteBuffer[] videoEncIn=videoEncoder.getInputBuffers();
				ByteBuffer[] videoEncOut=videoEncoder.getOutputBuffers();
				audioEncoder.start();
				ByteBuffer[] audioEncIn=audioEncoder.getInputBuffers();
				ByteBuffer[] audioEncOut=audioEncoder.getOutputBuffers();
				videoDecoder.start();
				ByteBuffer[] videoIn=videoDecoder.getInputBuffers();
				ByteBuffer[] videoOut=videoDecoder.getOutputBuffers();
				audioDecoder.start();
				ByteBuffer[] audioIn=audioDecoder.getInputBuffers();
				ByteBuffer[] audioOut=audioDecoder.getOutputBuffers();

				int outAudioTrack=-1;
				int outVideoTrack=-1;
				boolean muxerRunning=false;
				lastFramePTS=-1;

				MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
				ByteBuffer buffer=ByteBuffer.allocate(videoW*videoH*3);
				byte[] buf=new byte[videoW*videoH*3];
				byte[] frameBuffer=new byte[videoW*videoH*3];
				int read=0;
				long pts=-1;
				do{
					read = extractor.readSampleData(buffer, 0);
					int track=extractor.getSampleTrackIndex();
					long time=extractor.getSampleTime();
					//if(time>30000000) break;
					if((time/1000000)!=pts) {
						//Log.d(TAG, "Track: " + (track == audioTrack ? "audio" : "video") + ", read " + read + ", time " +(time/1000000));
						callback.onProgressUpdated((int)(time/1000), (int)(videoFormat.getLong(MediaFormat.KEY_DURATION)/1000));
						pts=(time/1000000);
					}
					//buffer.position(0);
					if(track==audioTrack){
						int inIndex = audioDecoder.dequeueInputBuffer(1000);
						if(inIndex>=0) {
							buffer.position(0);
							buffer.get(buf, 0, read);
							audioIn[inIndex].position(0);
							audioIn[inIndex].put(buf, 0, read);
							audioDecoder.queueInputBuffer(inIndex, 0, read, extractor.getSampleTime(), extractor.getSampleFlags());
						}
						int outIndex = audioDecoder.dequeueOutputBuffer(info, 1000);
						if (outIndex >= 0) {
							//Log.v(TAG, "decoded audio " + info.size+", off "+info.offset);
							audioOut[outIndex].position(info.offset);
							audioOut[outIndex].get(buf, 0, info.size);
							//outAudio.write(buf, 0, info.size);
							audioDecoder.releaseOutputBuffer(outIndex, false);

							inIndex=audioEncoder.dequeueInputBuffer(-1);
							if(inIndex>=0){
								audioEncIn[inIndex].position(0);
								audioEncIn[inIndex].put(buf, 0, info.size);
								audioEncoder.queueInputBuffer(inIndex, 0, info.size, info.presentationTimeUs, 0);
							}else{
								throw new Exception("What dafuq");
							}
						}else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
							audioOut = audioDecoder.getOutputBuffers();
						}

					}else if(track==videoTrack){
						int inIndex = videoDecoder.dequeueInputBuffer(1000);
						if(inIndex>=0) {
							buffer.position(0);
							buffer.get(buf, 0, read);
							videoIn[inIndex].position(0);
							videoIn[inIndex].put(buf, 0, read);
							videoDecoder.queueInputBuffer(inIndex, 0, read, extractor.getSampleTime(), extractor.getSampleFlags());
						}
						int outIndex = videoDecoder.dequeueOutputBuffer(info, 1000);
						if (outIndex >= 0) {
							//Log.v(TAG, "decoded video " + info.size+", off "+info.offset);
							videoOut[outIndex].position(info.offset);
							videoOut[outIndex].get(buf, 0, info.size);
							//outAudio.write(buf, 0, info.size);

							videoDecoder.releaseOutputBuffer(outIndex, false);

							if(isQualcomm)
								QualcommFormatConverter.qcom_convert(buf, frameBuffer, videoW, videoH);
							else
								System.arraycopy(buf, 0, frameBuffer, 0, buf.length);

							resizeYuvFrame(frameBuffer, buf, videoW, videoH, sampleSize);
							inIndex = videoEncoder.dequeueInputBuffer(-1);
							if (inIndex >= 0) {
								videoEncIn[inIndex].position(0);
								int canWrite = Math.min(resizedYuvDataSize, videoEncIn[inIndex].remaining());
								videoEncIn[inIndex].put(buf, 0, canWrite);
								videoEncoder.queueInputBuffer(inIndex, 0, canWrite, info.presentationTimeUs, 0);
							} else {
								throw new Exception("What dafuq");
							}
						}else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
							videoOut = videoDecoder.getOutputBuffers();
						}
					}

					int outIndex = audioEncoder.dequeueOutputBuffer(info, 100);
					if (outIndex >= 0) {
						//Log.v(TAG, "encoded audio " + info.size+", off "+info.offset);
						audioEncOut[outIndex].position(info.offset);
						if(outAudioTrack!=-1 && outVideoTrack!=-1) {
							if(pendingSamples.size()>0){
								while(pendingSamples.size()>0 && pendingSamples.get(0).ptime<info.presentationTimeUs){
									pendingSamples.get(0).writeToMuxer(muxer, outVideoTrack, outAudioTrack);
									pendingSamples.remove(0);
									Log.i(TAG, "Remaining pending samples: "+pendingSamples.size());
								}
							}
							if(info.presentationTimeUs>=lastFramePTS)
								muxer.writeSampleData(outAudioTrack, audioEncOut[outIndex], info);
							else
								Log.w(TAG, "Dropped audio frame with wrong timestamp! "+info.presentationTimeUs);
							lastFramePTS=info.presentationTimeUs;
						}else{
							PendingSample ps=new PendingSample();
							ps.data=new byte[info.size];
							ps.flags=info.flags;
							ps.ptime=info.presentationTimeUs;
							ps.track=1;
							audioEncOut[outIndex].get(ps.data, 0, info.size);
							pendingSamples.add(ps);
						}
						//audioEncOut[outIndex].position(info.offset);
						//audioEncOut[outIndex].get(buf, 0, info.size);
						//outAudio.write(buf, 0, info.size);
						audioEncoder.releaseOutputBuffer(outIndex, false);
					}else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						audioEncOut = audioEncoder.getOutputBuffers();
					}else if(outIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
						Log.e(TAG, "!!!! AUDIO OUT FORMAT CHANGED "+audioEncoder.getOutputFormat());
						if(outAudioTrack==-1){
							outAudioTrack=muxer.addTrack(audioEncoder.getOutputFormat());
						}
					}
					outIndex = videoEncoder.dequeueOutputBuffer(info, 100);
					if (outIndex >= 0) {
						//Log.v(TAG, "encoded video " + info.size+", off "+info.offset);
						if(outVideoTrack!=-1 && outAudioTrack!=-1) {
							if(pendingSamples.size()>0){
								while(pendingSamples.size()>0 && pendingSamples.get(0).ptime<info.presentationTimeUs){
									pendingSamples.get(0).writeToMuxer(muxer, outVideoTrack, outAudioTrack);
									pendingSamples.remove(0);
									Log.i(TAG, "Remaining pending samples: "+pendingSamples.size());
								}
							}
							//if(info.presentationTimeUs>=lastFramePTS)
							muxer.writeSampleData(outVideoTrack, videoEncOut[outIndex], info);
							//else
							//	Log.w(TAG, "Dropped video frame with wrong timestamp!");
							//lastFramePTS=info.presentationTimeUs;
						}else{
							PendingSample ps=new PendingSample();
							ps.data=new byte[info.size];
							ps.flags=info.flags;
							ps.ptime=info.presentationTimeUs;
							ps.track=2;
							videoEncOut[outIndex].get(ps.data, 0, info.size);
							pendingSamples.add(ps);
						}
						//audioEncOut[outIndex].position(info.offset);
						//audioEncOut[outIndex].get(buf, 0, info.size);
						//outAudio.write(buf, 0, info.size);
						videoEncoder.releaseOutputBuffer(outIndex, false);
					}else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
						videoEncOut = audioEncoder.getOutputBuffers();
					}else if(outIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
						Log.e(TAG, "!!!! VIDEO OUT FORMAT CHANGED "+videoEncoder.getOutputFormat());
						if(outVideoTrack==-1){
							outVideoTrack=muxer.addTrack(videoEncoder.getOutputFormat());
						}
					}

					if(outVideoTrack!=-1 && outAudioTrack!=-1 && !muxerRunning){
						muxerRunning=true;
						muxer.start();
							/*Log.i(TAG, "Adding "+pendingSamples.size()+" pending samples...");
							for(PendingSample ps:pendingSamples){
								Log.v(TAG, "Sample time = "+ps.ptime+", size="+ps.data.length);
								info.set(0, ps.data.length, ps.ptime, ps.flags);
								buffer.position(0);
								buffer.limit(buffer.capacity());
								buffer.put(ps.data);
								muxer.writeSampleData(ps.track==1 ? outVideoTrack : outAudioTrack, buffer, info);
							}
							pendingSamples.clear();*/
					}

				}while(extractor.advance() && !canceled);
				extractor.release();
				audioDecoder.stop();
				audioDecoder.release();
				videoDecoder.stop();
				videoDecoder.release();
				audioEncoder.stop();
				audioEncoder.release();
				videoEncoder.stop();
				videoEncoder.release();
				muxer.stop();
				muxer.release();
				Log.i(TAG, "End of stream!");
				if(canceled){
					new File(dstFile).delete();
					return;
				}
				callback.onConversionCompleted();
			}catch(Exception x){
				callback.onConversionFailed(x.getMessage());
				Log.w(TAG, x);
			}
			running=false;
		}
	}

	public static interface Callback{
		public void onProgressUpdated(int done, int total);
		public void onConversionCompleted();
		public void onConversionFailed(String error);
		public void onReady();
	}

	private static void resizeYuvFrame(byte[] from, byte[] to, int w, int h, int sample){
		int tw=w/sample;
		int th=h/sample;
		// Y
		for(int y=0;y<th;y++){
			for(int x=0;x<tw;x++){
				to[y*tw+x]=from[y*sample*w+x*sample];
			}
		}
		// UV
		int offset=tw*th;
		for(int y=0;y<th/2;y++){
			for(int x=0;x<tw/2;x++){
				int uIndex=y*sample*w+x*sample*2;
				to[offset]=from[w*h+uIndex]; // u
				to[offset+1]=from[w*h+uIndex+1]; // v
				offset+=2;
			}
		}
	}

	private static class PendingSample{
		int flags;
		long ptime;
		byte[] data;
		int track;

		public void writeToMuxer(MediaMuxer muxer, int audioTrack, int videoTrack){
			MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
			info.set(0, data.length, ptime, flags);
			ByteBuffer buffer=ByteBuffer.wrap(data);
			muxer.writeSampleData(track==1 ? videoTrack : audioTrack, buffer, info);
		}
	}
}
