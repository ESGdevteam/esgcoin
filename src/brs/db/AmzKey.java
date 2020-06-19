package brs.db;

import org.jooq.Record;

public interface AmzKey {

  interface Factory<T> {
    AmzKey newKey(T t);

    AmzKey newKey(Record rs);
  }

  long[] getPKValues();

  interface LongKeyFactory<T> extends Factory<T> {
    @Override
    AmzKey newKey(Record rs);

    AmzKey newKey(long id);

  }

  interface LinkKeyFactory<T> extends Factory<T> {
    AmzKey newKey(long idA, long idB);
  }
}
