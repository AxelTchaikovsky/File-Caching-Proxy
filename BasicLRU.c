#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>


int main() {
    int fd = 0;
    printf("fd = %d\n", fd = open("A", O_CREAT));
    char *buf = "Big red fox working.";
    write(fd, (void *)buf, strlen(buf));
    char *read_buf;
    ssize_t read_bytes = read(fd, read_buf, strlen(buf));
    printf("read: %ld %s", read_bytes, read_buf);
    close(fd);
}