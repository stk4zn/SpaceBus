package sean.k.uts2120;

import java.util.Iterator;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.widget.ViewFlipper;

public class GameThread extends Thread implements SensorEventListener {

	private Game game;

	private SurfaceHolder _surfaceHolder;
	ViewFlipper menuFlipper;
	private SharedPreferences data;

	int framesAfterDeath;
	final static int FRAMES_AFTER_DEATH = 5;
	final static long DELAY_AFTER_LEVEL = 1500L;

	private boolean running;
	private boolean paused;

	private SensorEvent tiltEvent;
	private MotionEvent touchEventNew;
	private MotionEvent touchEventBuffer;
	private float tiltLastX;
	private float tiltLastY;
	private float tiltLastZ;
	private float tiltDeltaX;
	private float tiltDeltaY;
	private float tiltDeltaZ;
	private float accelerationX;

	private boolean tiltInitialized;
	final static float TILTX_NOISE = .05f;
	final static float TILTY_NOISE = 2f;
	private SensorManager sensorManager;
	private Sensor tiltSensor;
	private GameCanvas panel;
	private GameScreen currentScreen;
	private GameplayScreen gameplayScreen;

	/*
	 * this is the main thread on which the game is run. It consists of
	 * UpdateGameState, UpdateInput, UpdateAI, UpdatePhysics, UpdateAnimation,
	 * and UpdateGraphics phases. UpdateSound will be added soon. This class
	 * holds the Game object that holds all gamestate information. It also holds
	 * the current screen to be drawn. There are two states of this thread --
	 * paused and unpaused. When this thread is paused, the game and its
	 * entities do not update, but they may still be drawn. When it is unpaused
	 * gameplay is running. To change screens you call pause(GameScreen)
	 * specifying an instance of whatever screen you want to create. All input
	 * is buffered and handled at once during the UpdateInput state. The
	 * UpdatePhysics state holds the mechanics of the game including collision
	 * handling. The graphics are mostly handled in the DrawingPanel class.
	 */

	public GameThread(Game theGame, SharedPreferences theData,
			SurfaceHolder surfaceHolder, GameCanvas thePanel) {

		game = theGame;
		data = theData;
		_surfaceHolder = surfaceHolder;
		panel = thePanel;

		menuFlipper = (ViewFlipper) ((Activity) getContext())
				.findViewById(R.id.game_menu);
		// pausedScreen = new PausedScreen(this);
		gameplayScreen = new GameplayScreen(this);
		currentScreen = gameplayScreen; // so there is no null pointer
		framesAfterDeath = 0;

	}

	public void startLevel(Level theLevel) {
		game.setLevel(theLevel);
		game.renderLevel(panel.getContext(), theLevel);
		theLevel.initialize();
	}

	public void exitLevel(Level theLevel) {
		game.levelExit();
		game.unrenderLevel(theLevel);
		save();
	}

	public void restartLevel() {
		game.levelReset();
		game.renderLevel(panel.getContext(), game.getCurrentLevel());
		game.getCurrentLevel().initialize();
	}

	public Game getGame() {
		return game;
	}

	public void setAccelerometer(SensorManager manager) {
		sensorManager = manager;
		tiltSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		sensorManager.registerListener(this, tiltSensor,
				SensorManager.SENSOR_DELAY_GAME);
	}

	public void setTouchInput(MotionEvent mEvent) {
		touchEventNew = mEvent;
	}

	public void setRunning(boolean isRunning) {
		running = isRunning;
	}

	public boolean isPaused() {
		return paused;
	}

	public void pause(GameMenu menu) {
		Log.d(GameActivity.DEBUG, "pausing GameThread and opening menu");
		getGame().paused = true;

		paused = true;
		touchEventBuffer = null;
		save();
		menu.execute();

	}

	public void pause() {
		Log.d(GameActivity.DEBUG, "pausing GameThread without menu");
		getGame().paused = true;
		paused = true;
		touchEventBuffer = null;
	}

	public void pause(GameScreen screen) {
		Log.d(GameActivity.DEBUG, "pausing GameThread");
		getGame().paused = true;

		currentScreen.exit();
		currentScreen = screen;
		currentScreen.enter(panel.getContext());
		paused = true;
		touchEventBuffer = null;
		save();
	}

	public void unpause() {
		Log.d(GameActivity.DEBUG, "Unpausing GameThread");
		// unpause from other gamescreen
		if (currentScreen != gameplayScreen) {
			currentScreen.exit();
			currentScreen = gameplayScreen;
			currentScreen.enter(panel.getContext());
			getGame().paused = false;
			paused = false;
			touchEventBuffer = null;
		} else { // unpause from dialog box
			currentScreen = gameplayScreen;
			getGame().paused = false;

			paused = false;
			touchEventBuffer = null;

		}

	}

	public Context getContext() {
		return panel.getContext();
	}

	public GameScreen getScreen() {
		return currentScreen;
	}

	private void initialize() {

		Log.d(GameActivity.DEBUG, "Thread initializing");
		tiltInitialized = false;
		tiltEvent = null;
		tiltLastX = 0;
		tiltLastY = 0;
		tiltLastZ = 0;

		game.renderGame(getContext());

		if (getGame().started == false) {
			Log.d(GameActivity.DEBUG, "Game is not yet started.");
			getGame().gameReset();
			Log.d(GameActivity.DEBUG, "Starting First Level");
			startLevel(new LevelEarthMoon(getGame()));
			getGame().started = true;
			unpause();
			// startLevel(new LevelDebug(getGame()));
		} else {
			Log.d(GameActivity.DEBUG, "Game already started.");
			if (getGame().paused) {
				Log.d(GameActivity.DEBUG, "Game was previously paused. Pausing");
				pause();
			} else {
				Log.d(GameActivity.DEBUG, "Game was previously unpaused");
				unpause();

			}
		}
	}

	public void quit() {
		panel.getContext().startActivity(
				new Intent(getContext(), MainMenu.class));
		((GameActivity) getContext()).finish();
	}

	@Override
	public void run() {

		initialize();

		while (running) {

			updateGameState();
			updateInput();

			if (!paused) {
				updateAI();
				updatePhysics();

				// update sound
			}
			updateAnimation();
			updateGraphics();

			try {
				sleep((long) Game.THREAD_WAIT_TIME);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

		}

	}

	private void updateGameState() {

		currentScreen.update();

		if (!paused) {

			game.update();

			game.getCurrentLevel().update();

			if (game.getCurrentLevel().isComplete()) {
				game.getCurrentLevel().complete();
				/*
				 * pause(new LevelCompleteScreen(this,
				 * LevelCompleteScreen.BUTTON_WAIT_TIME));
				 */
				
				if (game.getCurrentLevel().nextLevel()!=null){
					pause(new LevelCompleteMenu((GameActivity) getContext(),
							DELAY_AFTER_LEVEL));
				} else{
					pause(new GameCompleteMenu((GameActivity) getContext(),
							DELAY_AFTER_LEVEL));
				}

				return;
			}

			game.addEntitiesFromPending();

			game.updateEntities();

			game.getPlayer().update();

			if (game.getPlayer().dead) {

				if (framesAfterDeath == 0) {
					game.getPlayer().die();
				}

				framesAfterDeath++;

				if (framesAfterDeath > FRAMES_AFTER_DEATH) {
					framesAfterDeath = 0;
					pause(new GameOverMenu((GameActivity) getContext()));
				}
			}

		}

		// Log.d("ANIMATIONS:", ""+game.getAnimations().size());

	}

	private void updateInput() {

		if (paused) {
			return;
		}
		// act on input

		// touch Event
		if (touchEventNew != null) {
			touchEventBuffer = touchEventNew;
			touchEventNew = null;

		}

		// tilt stuff
		if (tiltEvent != null && !paused) {

			float tiltX = tiltEvent.values[0];
			float tiltY = tiltEvent.values[1];
			float tiltZ = tiltEvent.values[2];

			if (!tiltInitialized) {
				tiltLastX = tiltX;
				tiltLastY = tiltY;
				tiltLastZ = tiltZ;
				tiltInitialized = true;

			} else {

				// if device is upside down, register x tilt backwards

				tiltDeltaX = tiltLastX - tiltX;
				if (tiltLastZ < 0f) {
					tiltDeltaX = tiltDeltaX * -1;
				}
				tiltDeltaY = tiltLastY - tiltY;
				tiltDeltaZ = tiltLastZ - tiltZ;

				if (Math.abs(tiltDeltaX) < TILTX_NOISE) {
					tiltDeltaX = 0f;
				}
				if (Math.abs(tiltDeltaY) < TILTY_NOISE) {
					tiltDeltaY = 0f;
				}
				if (Math.abs(tiltDeltaZ) < TILTX_NOISE) {
					tiltDeltaZ = 0f;
				}

				tiltLastX = tiltX;
				tiltLastY = tiltY;
				tiltLastZ = tiltZ;

				game.getCurrentLevel().debugAccelerometer(tiltLastX,
						tiltDeltaX, tiltLastY, tiltDeltaY, tiltLastZ,
						tiltDeltaZ);
			}
		}

		if (touchEventBuffer != null) {
			// listen for touch events
			processTouchEvent();

		}

	}

	private void updateAI() {
		for (Enemy enemy : game.getEnemies()) {
			enemy.ai();
		}
	}

	private void processTouchEvent() {

		if (touchEventBuffer.getAction() == MotionEvent.ACTION_DOWN) {

			currentScreen.touch(touchEventBuffer);

		} else if (touchEventBuffer.getAction() == MotionEvent.ACTION_MOVE
				&& !paused) {
			currentScreen.touch(touchEventBuffer);
		}

		touchEventBuffer = null;
	}

	private void updatePhysics() {

		// game mechanics

		accelerationX = tiltDeltaX * Game.tiltSensitivity;
		/*
		 * if (accelerationX>0 &&
		 * accelerationX>game.getPlayer().getMaxAccelerationX()){ accelerationX
		 * = game.getPlayer().getMaxAccelerationX(); }
		 * 
		 * if (accelerationX<0 &&
		 * accelerationX<(game.getPlayer().getMaxAccelerationX()*-1)){
		 * accelerationX = game.getPlayer().getMaxAccelerationX()*-1; }
		 */

		game.getPlayer().incVelocityX(accelerationX);

		// RT = Shots*asteroids +Shots*enemies + enemyShot + asteroid + pickups
		// + enemies

		// player shots hit asteroids and enemies
		for (Shot shot : game.getLaserShots()) {
			for (Asteroid asteroid : game.getAsteroids()) {
				if (shot.intersects(asteroid.getHitBox())) {
					shot.hit(asteroid);
				}
			}
			for (Enemy enemy : game.getEnemies()) {
				if (shot.intersects(enemy.getHitBox())) {
					shot.hit(enemy);
				}
			}
		}

		// enemy shots hit player and asteroids
		for (EnemyShot shot : game.getEnemyShots()) {
			if (shot.intersects(game.getPlayer().getHitBox())) {
				game.getPlayer().damage(shot.getDamage());
				shot.setRemove(true);
			}

			for (Asteroid asteroid : game.getAsteroids()) {
				if (shot.intersects(asteroid.getHitBox())) {
					asteroid.decHealth(shot.getDamage());
					shot.setRemove(true);
				}
			}
		}

		for (PickUp pickup : game.getPickUps()) {
			if (pickup.intersects(game.getPlayer().getHitBox())) {
				pickup.pickUp();
			}
		}

		// player impacts asteroid or enemies
		for (Asteroid ast : game.getAsteroids()) {
			if (ast.intersects(game.getPlayer().getHitBox())) {
				game.getPlayer().collide(ast);
			}
		}

		for (Enemy enemy : game.getEnemies()) {
			if (enemy.intersects(game.getPlayer().getHitBox())) {
				game.getPlayer().collide(enemy);
			}
		}

	}

	private void updateAnimation() {

		Iterator<GameAnimation> animIterator = game.getAnimations().iterator();
		while (animIterator.hasNext()) {
			GameAnimation anim = animIterator.next();
			if (anim.UIAnimation || !paused) {
				anim.animate();
				if (anim.remove()) {

					animIterator.remove(); // put in gameState?

				}
			}
		}

	}

	private void updateGraphics() {

		Canvas c;

		c = null;

		try {

			c = _surfaceHolder.lockCanvas(null);
			synchronized (_surfaceHolder) {

				panel.postInvalidate();
			}

		} finally {

			if (c != null) {
				_surfaceHolder.unlockCanvasAndPost(c);
			}

		}

	}

	public void setData(SharedPreferences theData) {
		data = theData;
	}

	public void save() {
		SharedPreferences.Editor edit = data.edit();
		// Date now = new Date();
		edit.clear();
		edit.putInt(GameActivity.HIGH_SCORE_KEY, game.getHighScore());
		edit.commit();
	}

	public void onPause() {
		save();
		sensorManager.unregisterListener(this);
	}

	public void onResume() {
		if (sensorManager != null) {
			sensorManager.registerListener(this, tiltSensor,
					SensorManager.SENSOR_DELAY_GAME);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onSensorChanged(SensorEvent event) {

		tiltEvent = event;

	}

}
