package com.mycompanyname.zero.saas.feature;

/**
 * Value type of a feature. Drives server-side validation of stored values and tells the admin UI
 * which editor to render (switch / number input / text input).
 */
public enum FeatureType {
    BOOLEAN,
    NUMBER,
    STRING
}
