/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jasper.util;

/**
 * 
 * The FastRemovalDequeue is a Dequeue that supports constant time removal of
 * entries. This is achieved by using a doubly linked list and wrapping any object
 * added to the collection with an Entry type, that is returned to the consumer.
 * When removing an object from the list, the consumer provides this Entry object.
 *
 * The Entry type is mostly opaque to the consumer itself. The consumer can only
 * retrieve the original object - named content - from the Entry.
 *
 * The Entry object contains the links pointing to the neighbours in the doubly
 * linked list, so that removal of an Entry does not need to search for it but
 * instead can be done in constant time.
 *
 * A typical use of the FastRemovalDequeue is a list of entries in sorted order,
 * where the sort position of an object will only switch to first or last.
 *
 * Whenever the sort position needs to change, the consumer can remove the object
 * and reinsert it in front or at the end in constant time.
 * So keeping the list sorted is very cheap.
 *
 */
public class FastRemovalDequeue<T> {

    /** First element of the queue. */
    private Entry<T> first;
    /** Last element of the queue. */
    private Entry<T> last;

    /** Initialize empty queue. */
    public FastRemovalDequeue() {
        first = null;
        last = null;
    }

    /**
     * Adds an object to the start of the list and returns the entry created for
     * said object. The entry can later be reused for moving the entry.
     * 
     * @param object the object to prepend to the start of the list.
     * @return an entry for use when the object should be moved.
     * */
    public Entry<T> push(final T object) {
        Entry<T> entry = new Entry<T>(object);
        if (first == null) {
            first = last = entry;
        } else {
            first.setPrevious(entry);
            entry.setNext(first);
            first = entry;
        }

        return entry;
    }

    /**
     * Adds an object to the end of the list and returns the entry created for
     * said object. The entry can later be reused for moving the entry.
     * 
     * @param object the object to append to the end of the list.
     * @return an entry for use when the object should be moved.
     * */
    public Entry<T> unpop(final T object) {
        Entry<T> entry = new Entry<T>(object);
        if (first == null) {
            first = last = entry;
        } else {
            last.setNext(entry);
            entry.setPrevious(last);
            last = entry;
        }

        return entry;
    }

    /**
     * Removes the first element of the list and returns its content.
     * 
     * @return the content of the first element of the list.
     **/
    public T unpush() {
        T content = null;
        if (first != null) {
            content = first.getContent();
            first = first.getNext();
            if (first != null) {
                first.setPrevious(null);
            }
        }
        return content;
    }

    /**
     * Removes the last element of the list and returns its content.
     * 
     * @return the content of the last element of the list.
     **/
    public T pop() {
        T content = null;
        if (last != null) {
            content = last.getContent();
            last = last.getPrevious();
            if (last != null) {
                last.setNext(null);
            }
        }
        return content;
    }

    /**
     * Removes any element of the list and returns its content.
     **/
    public void remove(final Entry<T> element) {
        Entry<T> next = element.getNext();
        Entry<T> prev = element.getPrevious();
        if (next != null) {
            next.setPrevious(prev);
        } else {
            last = prev;
        }
        if (prev != null) {
            prev.setNext(next);
        } else {
            first = next;
        }
    }

    /**
     * Moves the element in front.
     *
     * Could also be implemented as remove() and
     * push(), but explicitely coding might be a bit faster.
     * 
     * @param element the entry to move in front.
     * */
    public void moveFirst(final Entry<T> element) {
        if (element.getPrevious() != null) {
            Entry<T> prev = element.getPrevious();
            Entry<T> next = element.getNext();
            prev.setNext(next);
            if (next != null) {
                next.setPrevious(prev);
            } else {
                last = prev;
            }
            first.setPrevious(element);
            element.setNext(first);
            element.setPrevious(null);
            first = element;
        }
    }

    /**
     * Moves the element to the back.
     *
     * Could also be implemented as remove() and
     * unpop(), but explicitely coding might be a bit faster.
     * 
     * @param element the entry to move to the back.
     * */
    public void moveLast(final Entry<T> element) {
        if (element.getNext() != null) {
            Entry<T> next = element.getNext();
            Entry<T> prev = element.getPrevious();
            next.setPrevious(prev);
            if (prev != null) {
                prev.setNext(next);
            } else {
                first = next;
            }
            last.setNext(element);
            element.setPrevious(last);
            element.setNext(null);
            last = element;
        }
    }
}