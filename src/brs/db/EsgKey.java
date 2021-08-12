package brs.db;

import org.jooq.Record;

public interface EsgKey {

  interface Factory<T> {
    EsgKey newKey(T t);

    EsgKey newKey(Record rs);
  }

  long[] getPKValues();

  interface LongKeyFactory<T> extends Factory<T> {
    @Override
    EsgKey newKey(Record rs);

    EsgKey newKey(long id);

  }

  interface LinkKeyFactory<T> extends Factory<T> {
    EsgKey newKey(long idA, long idB);
  }
}
