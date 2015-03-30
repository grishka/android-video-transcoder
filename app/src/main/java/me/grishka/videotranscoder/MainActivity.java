package me.grishka.videotranscoder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;


public class MainActivity extends Activity{

	private VideoConverter videoConverter;
	private String chosenFile=null;
	private SeekBar videoBitrateSlider, audioBitrateSlider, scalingFactorSlider;
	private TextView videoBitrateValue, audioBitrateValue, scalingFactorValue;
	private SeekBar.OnSeekBarChangeListener seekBarListener=new SeekBar.OnSeekBarChangeListener(){
		@Override
		public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
			switch(seekBar.getId()){
				case R.id.video_bitrate_slider:
					videoBitrateValue.setText(progress+"");
					break;
				case R.id.audio_bitrate_slider:
					audioBitrateValue.setText(progress+"");
					break;
				case R.id.scaling_factor_slider:
					scalingFactorValue.setText((progress+1)+"");
					break;
			}
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar){

		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar){

		}
	};

	private static final int PICK_RESULT=101;

	@Override
	protected void onCreate(Bundle savedInstanceState){
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		findViewById(R.id.btn_pick).setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				Intent intent=new Intent(Intent.ACTION_PICK);
				intent.setType("video/*");
				startActivityForResult(intent, PICK_RESULT);
			}
		});
		findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener(){
			@Override
			public void onClick(View v){
				doConvert(chosenFile, ((TextView)findViewById(R.id.edit_output_file)).getText().toString());
			}
		});

		videoBitrateSlider=(SeekBar)findViewById(R.id.video_bitrate_slider);
		audioBitrateSlider=(SeekBar)findViewById(R.id.audio_bitrate_slider);
		scalingFactorSlider=(SeekBar)findViewById(R.id.scaling_factor_slider);
		videoBitrateValue=(TextView)findViewById(R.id.video_bitrate_value);
		audioBitrateValue=(TextView)findViewById(R.id.audio_bitrate_value);
		scalingFactorValue=(TextView)findViewById(R.id.scaling_factor_value);

		videoBitrateSlider.setOnSeekBarChangeListener(seekBarListener);
		audioBitrateSlider.setOnSeekBarChangeListener(seekBarListener);
		scalingFactorSlider.setOnSeekBarChangeListener(seekBarListener);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data){
		if(requestCode==PICK_RESULT && resultCode==RESULT_OK){
			chosenFile=data.getData().toString();
			((TextView)findViewById(R.id.file_name)).setText(chosenFile);
			findViewById(R.id.btn_start).setEnabled(true);
		}
	}

	private void doConvert(String fromFile, String toFile){
		final ProgressDialog progress=new ProgressDialog(this);
		progress.setTitle("Compressing video");
		progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		progress.setCancelable(false);
		progress.setButton(ProgressDialog.BUTTON_POSITIVE, "Cancel", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				videoConverter.cancel();
				progress.dismiss();
			}
		});
		progress.show();

		VideoConverter.Callback callback=new VideoConverter.Callback() {
			@Override
			public void onProgressUpdated(final int done, final int total) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						progress.setMax(total);
						progress.setProgress(done);
					}
				});
			}

			@Override
			public void onConversionCompleted() {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						progress.dismiss();
						videoConverter=null;
						Toast.makeText(MainActivity.this, "Done!", Toast.LENGTH_SHORT).show();
					}
				});
			}

			@Override
			public void onConversionFailed(final String error) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						progress.dismiss();
						new AlertDialog.Builder(MainActivity.this)
								.setTitle("Error")
								.setMessage(error)
								.setPositiveButton("OK", null)
								.show();
					}
				});
			}

			@Override
			public void onReady() {
				videoConverter.start();
			}
		};
		int scalingFactor=scalingFactorSlider.getProgress()+1;
		MediaMetadataRetriever mmr=new MediaMetadataRetriever();
		mmr.setDataSource(this, Uri.parse(fromFile));
		int vw=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
		int vh=Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
		mmr.release();
		videoConverter=new VideoConverter(fromFile, toFile, videoBitrateSlider.getProgress(), audioBitrateSlider.getProgress(), Math.max(vw, vh)/scalingFactor, callback, this);
	}
}
