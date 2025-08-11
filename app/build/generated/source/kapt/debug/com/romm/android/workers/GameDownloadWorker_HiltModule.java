package com.romm.android.workers;

import androidx.hilt.work.WorkerAssistedFactory;
import androidx.work.ListenableWorker;
import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.codegen.OriginatingElement;
import dagger.hilt.components.SingletonComponent;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

@Module
@InstallIn(SingletonComponent.class)
@OriginatingElement(
    topLevelClass = GameDownloadWorker.class
)
public interface GameDownloadWorker_HiltModule {
  @Binds
  @IntoMap
  @StringKey("com.romm.android.workers.GameDownloadWorker")
  WorkerAssistedFactory<? extends ListenableWorker> bind(
      GameDownloadWorker_AssistedFactory factory);
}
