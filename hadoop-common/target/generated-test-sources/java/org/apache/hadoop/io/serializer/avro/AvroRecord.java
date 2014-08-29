/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package org.apache.hadoop.io.serializer.avro;  
@SuppressWarnings("all")
@org.apache.avro.specific.AvroGenerated
public class AvroRecord extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = new org.apache.avro.Schema.Parser().parse("{\"type\":\"record\",\"name\":\"AvroRecord\",\"namespace\":\"org.apache.hadoop.io.serializer.avro\",\"fields\":[{\"name\":\"intField\",\"type\":\"int\"}]}");
  public static org.apache.avro.Schema getClassSchema() { return SCHEMA$; }
  @Deprecated public int intField;

  /**
   * Default constructor.
   */
  public AvroRecord() {}

  /**
   * All-args constructor.
   */
  public AvroRecord(java.lang.Integer intField) {
    this.intField = intField;
  }

  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return intField;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: intField = (java.lang.Integer)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }

  /**
   * Gets the value of the 'intField' field.
   */
  public java.lang.Integer getIntField() {
    return intField;
  }

  /**
   * Sets the value of the 'intField' field.
   * @param value the value to set.
   */
  public void setIntField(java.lang.Integer value) {
    this.intField = value;
  }

  /** Creates a new AvroRecord RecordBuilder */
  public static org.apache.hadoop.io.serializer.avro.AvroRecord.Builder newBuilder() {
    return new org.apache.hadoop.io.serializer.avro.AvroRecord.Builder();
  }
  
  /** Creates a new AvroRecord RecordBuilder by copying an existing Builder */
  public static org.apache.hadoop.io.serializer.avro.AvroRecord.Builder newBuilder(org.apache.hadoop.io.serializer.avro.AvroRecord.Builder other) {
    return new org.apache.hadoop.io.serializer.avro.AvroRecord.Builder(other);
  }
  
  /** Creates a new AvroRecord RecordBuilder by copying an existing AvroRecord instance */
  public static org.apache.hadoop.io.serializer.avro.AvroRecord.Builder newBuilder(org.apache.hadoop.io.serializer.avro.AvroRecord other) {
    return new org.apache.hadoop.io.serializer.avro.AvroRecord.Builder(other);
  }
  
  /**
   * RecordBuilder for AvroRecord instances.
   */
  public static class Builder extends org.apache.avro.specific.SpecificRecordBuilderBase<AvroRecord>
    implements org.apache.avro.data.RecordBuilder<AvroRecord> {

    private int intField;

    /** Creates a new Builder */
    private Builder() {
      super(org.apache.hadoop.io.serializer.avro.AvroRecord.SCHEMA$);
    }
    
    /** Creates a Builder by copying an existing Builder */
    private Builder(org.apache.hadoop.io.serializer.avro.AvroRecord.Builder other) {
      super(other);
    }
    
    /** Creates a Builder by copying an existing AvroRecord instance */
    private Builder(org.apache.hadoop.io.serializer.avro.AvroRecord other) {
            super(org.apache.hadoop.io.serializer.avro.AvroRecord.SCHEMA$);
      if (isValidValue(fields()[0], other.intField)) {
        this.intField = data().deepCopy(fields()[0].schema(), other.intField);
        fieldSetFlags()[0] = true;
      }
    }

    /** Gets the value of the 'intField' field */
    public java.lang.Integer getIntField() {
      return intField;
    }
    
    /** Sets the value of the 'intField' field */
    public org.apache.hadoop.io.serializer.avro.AvroRecord.Builder setIntField(int value) {
      validate(fields()[0], value);
      this.intField = value;
      fieldSetFlags()[0] = true;
      return this; 
    }
    
    /** Checks whether the 'intField' field has been set */
    public boolean hasIntField() {
      return fieldSetFlags()[0];
    }
    
    /** Clears the value of the 'intField' field */
    public org.apache.hadoop.io.serializer.avro.AvroRecord.Builder clearIntField() {
      fieldSetFlags()[0] = false;
      return this;
    }

    @Override
    public AvroRecord build() {
      try {
        AvroRecord record = new AvroRecord();
        record.intField = fieldSetFlags()[0] ? this.intField : (java.lang.Integer) defaultValue(fields()[0]);
        return record;
      } catch (Exception e) {
        throw new org.apache.avro.AvroRuntimeException(e);
      }
    }
  }
}
