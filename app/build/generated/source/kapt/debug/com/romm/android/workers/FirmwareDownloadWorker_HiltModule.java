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
    topLevelClass = FirmwareDownloadWorker.class
)
public interface FirmwareDownloadWorker_HiltModule {
  @Binds
  @IntoMap
  @StringKey("com.romm.android.workers.FirmwareDownloadWorker")
  WorkerAssistedFactory<? extends ListenableWorker> bind(
      FirmwareDownloadWorker_AssistedFactory factory);
}
