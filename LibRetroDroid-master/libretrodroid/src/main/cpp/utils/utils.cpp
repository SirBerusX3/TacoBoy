/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <iostream>
#include <fstream>
#include <unistd.h>

#include "utils.h"
#include "../log.h"

namespace libretrodroid {

Utils::ReadResult Utils::readFileAsBytes(const std::string &filePath) {
    std::ifstream fileStream(filePath);
    fileStream.seekg(0, std::ios::end);
    size_t size = fileStream.tellg();
    char* bytes = new char[size];
    fileStream.seekg(0, std::ios::beg);
    fileStream.read(bytes, size);
    fileStream.close();

    return ReadResult { size, bytes };
}

Utils::ReadResult Utils::readFileAsBytes(const int fileDescriptor) {
    // Operate on a dup()'d fd rather than fileDescriptor itself: fileDescriptor
    // is owned by the caller's VFSFile/FDWrapper, which closes it separately.
    // fdopen() tags a fd as FILE*-owned for fdsan; closing that fd with a
    // bare close() instead of fclose() (as this used to do) trips fdsan's
    // ownership check and aborts the process. Using a duplicate and fclose()-ing
    // it here keeps this function's cleanup self-contained and avoids a second,
    // later double-close of the original fd via FDWrapper's destructor.
    int duplicateFD = dup(fileDescriptor);
    FILE* file = fdopen(duplicateFD, "r");
    size_t size = getFileSize(file);

    char* bytes = new char[size];
    fread(bytes, sizeof(char), size, file);
    fclose(file);
    return ReadResult { size, bytes };
}

size_t Utils::getFileSize(FILE* file) {
    fseek(file, 0, SEEK_SET);
    fseek(file, 0, SEEK_END);
    size_t size = ftell(file);
    fseek(file, 0, SEEK_SET);
    return size;
}

const char* Utils::cloneToCString(const std::string &input) {
    char* result = new char[input.length() + 1];
    std::strcpy(result, input.c_str());
    return result;
}

} //namespace libretrodroid