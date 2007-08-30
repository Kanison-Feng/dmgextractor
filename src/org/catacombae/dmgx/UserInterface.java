/*-
 * Copyright (C) 2006 Erik Larsson
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.catacombae.dmgx;

interface UserInterface {
    public void reportProgressPercentage(int percentage);
    public void setTotalProgressLength(long len);
    public void addProgressRaw(long value);
    
    public static class NullUI implements UserInterface {
	public void reportProgressPercentage(int percentage) {}
	public void setTotalProgressLength(long len) {}
	public void addProgressRaw(long value) {}
    }
}
